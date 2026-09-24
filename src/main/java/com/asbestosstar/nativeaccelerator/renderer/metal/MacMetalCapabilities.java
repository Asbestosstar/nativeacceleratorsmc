package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Native macOS Metal capability probe used before SDL creates its GPU device.
 *
 * <p>This asks the active Metal driver instead of inferring general feature support from a Mac model,
 * OS version, or CPU architecture. Two additional safety rules are deliberate:</p>
 *
 * <ul>
 *   <li>Graphics ICB support is queried non-destructively with
 *       {@code supportsFeatureSet:MTLFeatureSet_macOS_GPUFamily2_v1}. The probe never creates an
 *       {@code MTLIndirectCommandBuffer}; unsupported-resource creation can abort the process under
 *       Apple's validation layer instead of returning a recoverable error.</li>
 *   <li>Any Metal device whose reported name identifies NVIDIA/GeForce/Quadro is hard-capped to the
 *       MacFamily1 compatibility tier. Legacy/OCLP stacks can expose newer family bits, while the
 *       underlying NVIDIA hardware still lacks MacFamily2 graphics ICB support.</li>
 * </ul>
 */
final class MacMetalCapabilities {
    // Public Metal/MTLDevice.h enum values.
    static final long FAMILY_APPLE1 = 1001L;
    static final long FAMILY_APPLE11 = 1011L;
    static final long FAMILY_MAC1 = 2001L;
    static final long FAMILY_MAC2 = 2002L;
    static final long FAMILY_METAL3 = 5001L;
    static final long FAMILY_METAL4 = 5002L;

    // MTLFeatureSet_macOS_GPUFamily2_v1. Apple documents this as the macOS ICB capability test.
    static final long FEATURE_SET_MACOS_GPU_FAMILY2_V1 = 10005L;

    enum Tier {
        APPLE_SILICON,
        MAC2_OR_NEWER,
        MAC1_COMPAT,
        UNKNOWN,
        UNAVAILABLE
    }

    record Result(
            boolean available,
            Tier tier,
            String deviceName,
            String hardwareModel,
            String osVersion,
            boolean mac1,
            boolean mac2,
            int highestAppleFamily,
            boolean metal3,
            boolean metal4,
            boolean mac2FeatureSet,
            boolean legacyNvidia,
            boolean unifiedMemory,
            boolean lowPower,
            boolean removable,
            long registryId,
            boolean indirectCommandBufferSupported,
            String indirectCommandBufferSupportDetail,
            String detail) {

        boolean modernIndirectCandidate() {
            return tier == Tier.APPLE_SILICON || tier == Tier.MAC2_OR_NEWER;
        }

        boolean modernIndirectSafe() {
            return modernIndirectCandidate() && indirectCommandBufferSupported && !legacyNvidia;
        }

        boolean needsMacFamily1OptIn() {
            return tier == Tier.MAC1_COMPAT;
        }

        boolean validationSafeByDefault() {
            return modernIndirectSafe();
        }

        boolean isAppleSilicon() {
            return tier == Tier.APPLE_SILICON;
        }

        String familySummary() {
            StringBuilder out = new StringBuilder(tier.name());
            if (mac1) out.append(" mac1");
            if (mac2) out.append(" mac2");
            if (highestAppleFamily > 0) out.append(" apple").append(highestAppleFamily);
            if (metal3) out.append(" metal3");
            if (metal4) out.append(" metal4");
            if (mac2FeatureSet) out.append(" mac2-feature-set");
            if (legacyNvidia) out.append(" nvidia-mac1-ceiling");
            if (unifiedMemory) out.append(" unified");
            if (lowPower) out.append(" low-power");
            if (removable) out.append(" removable");
            out.append(indirectCommandBufferSupported ? " icb=supported" : " icb=cpu-fallback");
            return out.toString();
        }
    }

    private MacMetalCapabilities() {}

    static Result current() {
        return Holder.CURRENT;
    }

    /** Backwards-compatible pure classification helper used by tests. */
    static Tier classify(boolean available, boolean mac1, boolean mac2,
                         int highestAppleFamily, boolean metal3, boolean metal4) {
        return classify(available, mac1, mac2, highestAppleFamily, metal3, metal4, false, false);
    }

    /**
     * Pure classification helper. NVIDIA is intentionally checked before all advertised modern-family
     * signals because OCLP/legacy stacks can expose capabilities that the physical GPU cannot execute.
     */
    static Tier classify(boolean available, boolean mac1, boolean mac2,
                         int highestAppleFamily, boolean metal3, boolean metal4,
                         boolean mac2FeatureSet, boolean legacyNvidia) {
        if (!available) return Tier.UNAVAILABLE;
        if (legacyNvidia) return Tier.MAC1_COMPAT;
        // Apple GPU families are only reported by Apple GPUs. Apple7 corresponds to M1-generation
        // functionality and later families continue upward from there.
        if (highestAppleFamily >= 7) return Tier.APPLE_SILICON;
        if (mac2 || mac2FeatureSet || metal3 || metal4) return Tier.MAC2_OR_NEWER;
        if (mac1) return Tier.MAC1_COMPAT;
        return Tier.UNKNOWN;
    }

    /** Device-name policy is deliberately conservative for macOS NVIDIA Metal implementations. */
    static boolean isLegacyNvidiaDeviceName(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) return false;
        String normalized = deviceName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (normalized.isEmpty()) return false;
        return normalized.contains("nvidia")
                || normalized.startsWith("geforce ")
                || normalized.contains(" geforce ")
                || normalized.startsWith("quadro ")
                || normalized.contains(" quadro ");
    }

    /**
     * Native graphics ICB is enabled only when two non-destructive Metal signals agree. Merely
     * advertising Metal3/4 is not sufficient, and NVIDIA is never allowed onto this path.
     */
    static boolean safeIndirectCommandBufferSupport(Tier tier,
                                                    boolean legacyNvidia,
                                                    boolean mac2Family,
                                                    int highestAppleFamily,
                                                    boolean supportsFeatureSetMethod,
                                                    boolean mac2FeatureSet) {
        if (legacyNvidia || !supportsFeatureSetMethod || !mac2FeatureSet) return false;
        boolean familyEvidence = mac2Family || highestAppleFamily >= 7;
        return familyEvidence && (tier == Tier.APPLE_SILICON || tier == Tier.MAC2_OR_NEWER);
    }

    private static final class Holder {
        private static final Result CURRENT = probe();
    }

    private static Result probe() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("mac") && !osName.contains("darwin")) {
            return unavailable("not macOS");
        }

        String model = sysctl("hw.model");
        String osVersion = System.getProperty("os.version", "unknown");

        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup metal = openMetal(arena);
            SymbolLookup objc = openObjc(arena, linker);

            MethodHandle createDefault = linker.downcallHandle(
                    required(metal, "MTLCreateSystemDefaultDevice"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            MethodHandle selRegisterName = linker.downcallHandle(
                    required(objc, "sel_registerName"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MemorySegment objcMsgSend = required(objc, "objc_msgSend");

            MethodHandle msgAddress = linker.downcallHandle(
                    objcMsgSend,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle msgBool = linker.downcallHandle(
                    objcMsgSend,
                    FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle msgBoolAddress = linker.downcallHandle(
                    objcMsgSend,
                    FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle msgBoolLong = linker.downcallHandle(
                    objcMsgSend,
                    FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            MethodHandle msgLong = linker.downcallHandle(
                    objcMsgSend,
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            MemorySegment device = (MemorySegment)createDefault.invoke();
            if (device == null || device.address() == 0L) {
                return new Result(false, Tier.UNAVAILABLE, "none", model, osVersion,
                        false, false, 0, false, false, false, false,
                        false, false, false, 0L,
                        false, "not queried: no Metal device",
                        "MTLCreateSystemDefaultDevice returned null");
            }

            ObjcSelectors selectors = new ObjcSelectors(arena, selRegisterName);
            boolean supportsFamilyMethod = bool(msgBoolAddress.invoke(
                    device, selectors.respondsToSelector, selectors.supportsFamily));
            boolean supportsFeatureSetMethod = bool(msgBoolAddress.invoke(
                    device, selectors.respondsToSelector, selectors.supportsFeatureSet));

            String name = stringProperty(device, selectors.name, selectors.utf8String, msgAddress);
            if (name.isBlank()) name = "Metal GPU";
            boolean legacyNvidia = isLegacyNvidiaDeviceName(name);

            boolean mac1 = false;
            boolean mac2 = false;
            boolean metal3 = false;
            boolean metal4 = false;
            int highestApple = 0;
            if (supportsFamilyMethod) {
                mac1 = bool(msgBoolLong.invoke(device, selectors.supportsFamily, FAMILY_MAC1));
                mac2 = bool(msgBoolLong.invoke(device, selectors.supportsFamily, FAMILY_MAC2));
                metal3 = bool(msgBoolLong.invoke(device, selectors.supportsFamily, FAMILY_METAL3));
                metal4 = bool(msgBoolLong.invoke(device, selectors.supportsFamily, FAMILY_METAL4));
                for (int family = (int)(FAMILY_APPLE11 - 1000L); family >= 1; family--) {
                    long raw = 1000L + family;
                    if (bool(msgBoolLong.invoke(device, selectors.supportsFamily, raw))) {
                        highestApple = family;
                        break;
                    }
                }
            }

            boolean mac2FeatureSet = supportsFeatureSetMethod
                    && bool(msgBoolLong.invoke(device, selectors.supportsFeatureSet,
                    FEATURE_SET_MACOS_GPU_FAMILY2_V1));

            boolean unified = boolProperty(device, selectors.hasUnifiedMemory, selectors.respondsToSelector,
                    msgBoolAddress, msgBool);
            boolean lowPower = boolProperty(device, selectors.isLowPower, selectors.respondsToSelector,
                    msgBoolAddress, msgBool);
            boolean removable = boolProperty(device, selectors.isRemovable, selectors.respondsToSelector,
                    msgBoolAddress, msgBool);
            long registryId = longProperty(device, selectors.registryID, selectors.respondsToSelector,
                    msgBoolAddress, msgLong);

            Tier tier = classify(true, mac1, mac2, highestApple, metal3, metal4,
                    mac2FeatureSet, legacyNvidia);
            boolean icbSupported = safeIndirectCommandBufferSupport(
                    tier, legacyNvidia, mac2, highestApple, supportsFeatureSetMethod, mac2FeatureSet);

            String icbDetail;
            if (legacyNvidia) {
                icbDetail = "disabled: NVIDIA Metal device is hard-capped to MacFamily1";
            } else if (!supportsFeatureSetMethod) {
                icbDetail = "disabled: MTLDevice does not expose supportsFeatureSet:";
            } else if (!mac2FeatureSet) {
                icbDetail = "disabled: supportsFeatureSet(macOS_GPUFamily2_v1)=false";
            } else if (!(mac2 || highestApple >= 7)) {
                icbDetail = "disabled: Mac2 feature set reported without matching Mac2/Apple7+ family evidence";
            } else {
                icbDetail = "supported: non-destructive supportsFeatureSet(macOS_GPUFamily2_v1) query passed";
            }

            String detail = (supportsFamilyMethod
                    ? "MTLDevice supportsFamily: queried"
                    : "MTLDevice does not expose supportsFamily:")
                    + "; "
                    + (supportsFeatureSetMethod
                    ? "supportsFeatureSet: queried"
                    : "supportsFeatureSet: unavailable")
                    + (legacyNvidia ? "; NVIDIA compatibility ceiling applied" : "");

            return new Result(true, tier, name, model, osVersion,
                    mac1, mac2, highestApple, metal3, metal4, mac2FeatureSet, legacyNvidia,
                    unified, lowPower, removable, registryId,
                    icbSupported, icbDetail, detail);
        } catch (Throwable failure) {
            String message = failure.getMessage();
            return new Result(false, Tier.UNKNOWN, "unknown", model, osVersion,
                    false, false, 0, false, false, false, false,
                    false, false, false, 0L,
                    false, "not queried: Metal probe failed",
                    failure.getClass().getSimpleName()
                            + (message == null || message.isBlank() ? "" : ": " + message));
        }
    }

    private static Result unavailable(String detail) {
        return new Result(false, Tier.UNAVAILABLE, "none", "unknown",
                System.getProperty("os.version", "unknown"),
                false, false, 0, false, false, false, false,
                false, false, false, 0L,
                false, "not queried: unavailable", detail);
    }

    private static SymbolLookup openMetal(Arena arena) {
        RuntimeException last = null;
        for (String name : new String[]{
                "/System/Library/Frameworks/Metal.framework/Metal",
                "Metal.framework/Metal",
                "Metal"}) {
            try {
                return SymbolLookup.libraryLookup(name, arena);
            } catch (RuntimeException failure) {
                last = failure;
            }
        }
        throw last != null ? last : new IllegalStateException("Could not open Metal.framework");
    }

    private static SymbolLookup openObjc(Arena arena, Linker linker) {
        SymbolLookup defaults = linker.defaultLookup();
        if (defaults.find("objc_msgSend").isPresent() && defaults.find("sel_registerName").isPresent()) {
            return defaults;
        }
        RuntimeException last = null;
        for (String name : new String[]{"/usr/lib/libobjc.A.dylib", "libobjc.A.dylib", "objc"}) {
            try {
                return SymbolLookup.libraryLookup(name, arena);
            } catch (RuntimeException failure) {
                last = failure;
            }
        }
        throw last != null ? last : new IllegalStateException("Could not open Objective-C runtime");
    }

    private static MemorySegment required(SymbolLookup lookup, String symbol) {
        return lookup.find(symbol).orElseThrow(() -> new IllegalStateException("Missing native symbol " + symbol));
    }

    private static boolean bool(Object value) {
        return value instanceof Number number && number.byteValue() != 0;
    }

    private static boolean boolProperty(MemorySegment object, MemorySegment selector, MemorySegment responds,
                                        MethodHandle msgBoolAddress, MethodHandle msgBool) throws Throwable {
        if (!bool(msgBoolAddress.invoke(object, responds, selector))) return false;
        return bool(msgBool.invoke(object, selector));
    }

    private static long longProperty(MemorySegment object, MemorySegment selector, MemorySegment responds,
                                     MethodHandle msgBoolAddress, MethodHandle msgLong) throws Throwable {
        if (!bool(msgBoolAddress.invoke(object, responds, selector))) return 0L;
        return ((Number)msgLong.invoke(object, selector)).longValue();
    }

    private static String stringProperty(MemorySegment object, MemorySegment selector, MemorySegment utf8,
                                         MethodHandle msgAddress) throws Throwable {
        MemorySegment nsString = (MemorySegment)msgAddress.invoke(object, selector);
        if (nsString == null || nsString.address() == 0L) return "";
        MemorySegment cString = (MemorySegment)msgAddress.invoke(nsString, utf8);
        if (cString == null || cString.address() == 0L) return "";
        MemorySegment bytes = cString.reinterpret(16 * 1024L);
        int length = 0;
        while (length < 16 * 1024 && bytes.get(ValueLayout.JAVA_BYTE, length) != 0) length++;
        if (length == 16 * 1024) return "";
        byte[] copy = new byte[length];
        for (int i = 0; i < length; i++) copy[i] = bytes.get(ValueLayout.JAVA_BYTE, i);
        return new String(copy, StandardCharsets.UTF_8);
    }

    private static String sysctl(String key) {
        Process process = null;
        try {
            process = new ProcessBuilder("/usr/sbin/sysctl", "-n", key)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(Duration.ofMillis(700).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "unknown";
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return line == null || line.isBlank() ? "unknown" : line.trim();
            }
        } catch (Throwable ignored) {
            return "unknown";
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static MemorySegment cString(Arena arena, String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        MemorySegment cString = arena.allocate(utf8.length + 1L, 1L);
        for (int i = 0; i < utf8.length; i++) cString.set(ValueLayout.JAVA_BYTE, i, utf8[i]);
        cString.set(ValueLayout.JAVA_BYTE, utf8.length, (byte)0);
        return cString;
    }

    private static final class ObjcSelectors {
        final MemorySegment respondsToSelector;
        final MemorySegment supportsFamily;
        final MemorySegment supportsFeatureSet;
        final MemorySegment name;
        final MemorySegment utf8String;
        final MemorySegment hasUnifiedMemory;
        final MemorySegment isLowPower;
        final MemorySegment isRemovable;
        final MemorySegment registryID;

        ObjcSelectors(Arena arena, MethodHandle selRegisterName) throws Throwable {
            respondsToSelector = sel(arena, selRegisterName, "respondsToSelector:");
            supportsFamily = sel(arena, selRegisterName, "supportsFamily:");
            supportsFeatureSet = sel(arena, selRegisterName, "supportsFeatureSet:");
            name = sel(arena, selRegisterName, "name");
            utf8String = sel(arena, selRegisterName, "UTF8String");
            hasUnifiedMemory = sel(arena, selRegisterName, "hasUnifiedMemory");
            isLowPower = sel(arena, selRegisterName, "isLowPower");
            isRemovable = sel(arena, selRegisterName, "isRemovable");
            registryID = sel(arena, selRegisterName, "registryID");
        }

        private static MemorySegment sel(Arena arena, MethodHandle register, String name) throws Throwable {
            return (MemorySegment)register.invoke(cString(arena, name));
        }
    }
}

