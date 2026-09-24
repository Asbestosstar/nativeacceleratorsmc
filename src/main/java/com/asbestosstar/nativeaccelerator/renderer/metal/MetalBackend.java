package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RenderPearl backend backed by SDL3's Metal GPU driver.
 *
 * <p>The backend is selected from runtime capabilities rather than Mac model allow-lists. SDL proves
 * that its Metal GPU driver is usable; before device creation Native Accelerator also asks the real
 * {@code MTLDevice} which GPU families it supports so legacy Mac1, Mac2+, and Apple Silicon can use
 * different safe feature profiles.</p>
 */
public final class MetalBackend implements GpuBackend {
    public static final String ENABLE_PROPERTY = "nativeaccelerator.renderer.metal";
    public static final String DRIVER_NAME = "metal";
    public static final String ALLOW_MAC_FAMILY1_PROPERTY = "nativeaccelerator.renderer.metal.allowMacFamily1";
    /** Force an actual Metal device attempt even when SDL's non-creating support probe says no. */
    public static final String FORCE_ATTEMPT_PROPERTY = "nativeaccelerator.renderer.metal.forceAttempt";
    /** Opt back into Apple Metal validation on MacFamily1. Unsafe on many OCLP/legacy drivers. */
    public static final String MAC1_VALIDATION_PROPERTY = "nativeaccelerator.renderer.metal.mac1Validation";

    private static final AtomicInteger ACTIVE_DEVICES = new AtomicInteger();
    private static volatile String lastProbeReason = "not probed";

    enum MetalCapabilityTier {
        APPLE_SILICON,
        MAC2_OR_NEWER,
        MAC1_COMPAT,
        UNKNOWN
    }

    /** auto (default), on/force, or off. */
    public static boolean shouldOffer() {
        String mode = System.getProperty(ENABLE_PROPERTY, "auto").trim().toLowerCase(Locale.ROOT);
        if (mode.equals("off") || mode.equals("false") || mode.equals("0") || mode.equals("disabled")) {
            return false;
        }
        // "on" is intentionally still a capability probe. It forces ordering, not an impossible device.
        return isSupported();
    }

    /** Probe the real Metal device first, then verify that SDL can expose its Metal GPU backend. */
    public static boolean isSupported() {
        try {
            if (!MetalInterop.classPresent(MetalInterop.SDL_GPU)) {
                lastProbeReason = "LWJGL SDL GPU binding is not present";
                return false;
            }

            MacMetalCapabilities.Result metal = MacMetalCapabilities.current();
            boolean allowMacFamily1 = shouldAllowMacFamily1(metal);
            if (MetalInterop.classPresent(MetalInterop.SDL_PROPERTIES)) {
                int props = createDeviceProperties(false, false, allowMacFamily1);
                try {
                    boolean supported = (Boolean)MetalInterop.sdlCall("SDL_GPUSupportsProperties", props);
                    String nativeSummary = metal.available()
                            ? metal.deviceName() + " [" + metal.familySummary() + "]"
                            : "native Metal probe unavailable (" + metal.detail() + ")";
                    lastProbeReason = supported
                            ? "Metal=" + nativeSummary + "; SDL reports Metal/MSL support"
                            : "Metal=" + nativeSummary + "; SDL_GPUSupportsProperties returned false: "
                                    + MetalInterop.lastSdlError();
                    return supported;
                } finally {
                    MetalInterop.propertiesCall("SDL_DestroyProperties", props);
                }
            }
            int msl = MetalInterop.sdl("SDL_GPU_SHADERFORMAT_MSL");
            boolean supported = MetalInterop.sdlBool("SDL_GPUSupportsShaderFormats", msl, DRIVER_NAME);
            lastProbeReason = supported
                    ? "SDL reports Metal/MSL support (legacy probe)"
                    : "SDL_GPUSupportsShaderFormats returned false: " + MetalInterop.lastSdlError();
            return supported;
        } catch (Throwable unavailable) {
            String message = unavailable.getMessage();
            lastProbeReason = unavailable.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + message);
            return false;
        }
    }

    /**
     * Classify from the actual MTLDevice first. SDL probing remains only a compatibility fallback for
     * unusual runtimes where the direct framework query is unavailable.
     */
    static MetalCapabilityTier detectCapabilityTier() {
        MacMetalCapabilities.Result metal = MacMetalCapabilities.current();
        if (metal.available()) {
            return switch (metal.tier()) {
                case APPLE_SILICON -> MetalCapabilityTier.APPLE_SILICON;
                case MAC2_OR_NEWER -> MetalCapabilityTier.MAC2_OR_NEWER;
                case MAC1_COMPAT -> MetalCapabilityTier.MAC1_COMPAT;
                case UNKNOWN, UNAVAILABLE -> MetalCapabilityTier.UNKNOWN;
            };
        }

        if (!MetalInterop.classPresent(MetalInterop.SDL_PROPERTIES)) return MetalCapabilityTier.UNKNOWN;
        try {
            if (supportsWithMacFamily1(false)) return MetalCapabilityTier.MAC2_OR_NEWER;
            if (shouldAllowMacFamily1(metal) && supportsWithMacFamily1(true)) {
                return MetalCapabilityTier.MAC1_COMPAT;
            }
        } catch (Throwable ignored) {
        }
        return MetalCapabilityTier.UNKNOWN;
    }

    private static boolean supportsWithMacFamily1(boolean allowMacFamily1) {
        int props = createDeviceProperties(false, false, allowMacFamily1);
        try {
            return (Boolean)MetalInterop.sdlCall("SDL_GPUSupportsProperties", props);
        } finally {
            MetalInterop.propertiesCall("SDL_DestroyProperties", props);
        }
    }

    private static boolean shouldAllowMacFamily1(MacMetalCapabilities.Result metal) {
        String override = System.getProperty(ALLOW_MAC_FAMILY1_PROPERTY);
        if (override != null) return Boolean.parseBoolean(override);
        if (metal.available()) {
            return metal.tier() == MacMetalCapabilities.Tier.MAC1_COMPAT
                    || metal.tier() == MacMetalCapabilities.Tier.UNKNOWN;
        }
        // Preserve compatibility with older/patched stacks if the direct query itself was unavailable.
        return true;
    }

    public static String lastProbeReason() {
        return lastProbeReason;
    }

    private static int createDeviceProperties(boolean debug) {
        return createDeviceProperties(debug, debug, shouldAllowMacFamily1(MacMetalCapabilities.current()));
    }

    private static int createDeviceProperties(boolean debug, boolean allowMacFamily1) {
        return createDeviceProperties(debug, debug, allowMacFamily1);
    }

    private static int createDeviceProperties(boolean debug, boolean verbose, boolean allowMacFamily1) {
        int props = ((Number)MetalInterop.propertiesCall("SDL_CreateProperties")).intValue();
        if (props == 0) throw new IllegalStateException("SDL_CreateProperties failed: " + MetalInterop.lastSdlError());
        try {
            MetalInterop.propertiesCall("SDL_SetStringProperty", props,
                    MetalInterop.sdlString("SDL_PROP_GPU_DEVICE_CREATE_NAME_STRING"), DRIVER_NAME);
            MetalInterop.propertiesCall("SDL_SetBooleanProperty", props,
                    MetalInterop.sdlString("SDL_PROP_GPU_DEVICE_CREATE_SHADERS_MSL_BOOLEAN"), true);
            MetalInterop.propertiesCall("SDL_SetBooleanProperty", props,
                    MetalInterop.sdlString("SDL_PROP_GPU_DEVICE_CREATE_DEBUGMODE_BOOLEAN"), debug);
            MetalInterop.propertiesCall("SDL_SetBooleanProperty", props,
                    MetalInterop.sdlString("SDL_PROP_GPU_DEVICE_CREATE_VERBOSE_BOOLEAN"), verbose);

            // SDL normally requires MacFamily2. Enable its documented MacFamily1 compatibility path
            // only when the direct Metal probe says it is needed (or when explicitly overridden).
            // Native Accelerator does not write to sRGB render targets through this backend.
            if (allowMacFamily1) {
                MetalInterop.propertiesCall("SDL_SetBooleanProperty", props,
                        MetalInterop.sdlString("SDL_PROP_GPU_DEVICE_CREATE_METAL_ALLOW_MACFAMILY1_BOOLEAN"), true);
            }
            return props;
        } catch (Throwable t) {
            MetalInterop.propertiesCall("SDL_DestroyProperties", props);
            if (t instanceof RuntimeException runtime) throw runtime;
            if (t instanceof Error error) throw error;
            throw new IllegalStateException("Failed to configure SDL Metal device properties", t);
        }
    }

    /** True while at least one Native Accelerator SDL/Metal device is alive. */
    public static boolean isActive() {
        return ACTIVE_DEVICES.get() > 0;
    }

    static void deviceOpened() {
        ACTIVE_DEVICES.incrementAndGet();
    }

    static void deviceClosed() {
        ACTIVE_DEVICES.updateAndGet(value -> Math.max(0, value - 1));
    }

    @Override
    public String getName() {
        return "Metal (SDL3 GPU)";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        String mode = System.getProperty(ENABLE_PROPERTY, "auto").trim().toLowerCase(Locale.ROOT);
        if (mode.equals("off") || mode.equals("false") || mode.equals("0") || mode.equals("disabled")) {
            throw new BackendCreationException(
                    "Metal backend disabled by -D" + ENABLE_PROPERTY + "=" + mode,
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
        if (!MetalInterop.classPresent(MetalInterop.SDL_GPU)) {
            throw new BackendCreationException(
                    "LWJGL SDL GPU binding is unavailable; add org.lwjgl:lwjgl-sdl:3.4.3",
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }

        boolean supported = isSupported();
        boolean forceAttempt = Boolean.parseBoolean(System.getProperty(FORCE_ATTEMPT_PROPERTY, "false"));
        if (!supported) {
            // An explicit Metal preference should be allowed to reach SDL_CreateGPUDeviceWithProperties.
            // This matters on patched/OCLP systems where capability reporting can be conservative.
            System.err.println("[Native Accelerator] SDL Metal support probe was negative ("
                    + lastProbeReason + "); attempting real device creation anyway"
                    + (forceAttempt ? " because forceAttempt=true" : " because Metal was explicitly selected"));
        }
    }

    @Override
    public void unloadLibrary() {
        // Minecraft owns SDL's process lifetime. Never SDL_Quit() from a backend candidate.
    }

    @Override
    public long createWindow(@Nullable String title, int width, int height, long flags) {
        Object result = MetalInterop.videoCall("SDL_CreateWindow", title == null ? "" : title, width, height, flags);
        return result == null ? 0L : ((Number)result).longValue();
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions options) throws BackendCreationException {
        boolean requestedDebug = options.logLevel() > 0 || options.useLabels() || options.useValidationLayers();
        MacMetalCapabilities.Result metal = MacMetalCapabilities.current();
        MetalCapabilityTier capabilityTier = detectCapabilityTier();
        boolean allowMacFamily1 = shouldAllowMacFamily1(metal);
        System.out.println("[Native Accelerator] Metal preflight: device=" + metal.deviceName()
                + ", tier=" + metal.tier()
                + ", families={mac1=" + metal.mac1()
                + ", mac2=" + metal.mac2()
                + ", apple=" + metal.highestAppleFamily()
                + ", metal3=" + metal.metal3()
                + ", metal4=" + metal.metal4() + "}"
                + ", mac2FeatureSet=" + metal.mac2FeatureSet()
                + ", legacyNvidia=" + metal.legacyNvidia()
                + ", icb=" + (metal.indirectCommandBufferSupported() ? "supported" : "CPU-fallback")
                + " (" + metal.indirectCommandBufferSupportDetail() + ")"
                + ", unified=" + metal.unifiedMemory()
                + ", lowPower=" + metal.lowPower()
                + ", removable=" + metal.removable()
                + ", model=" + metal.hardwareModel()
                + ", macOS=" + metal.osVersion());

        // SDL's Metal backend implements debug mode by setting MTL_DEBUG_LAYER=1 *before*
        // MTLCreateSystemDefaultDevice(). Apple validation then remains enabled for the entire
        // process. Legacy/OCLP MacFamily1 drivers can abort inside device creation while the
        // validation layer probes graphics ICB support, before our RenderPearl feature mask is
        // even installed. Unless the user explicitly opts back in, only enable Metal validation
        // when the non-destructive capability query proves MacFamily2-or-newer support. Keep SDL verbose logs enabled.
        boolean compatibilityTier = capabilityTier == MetalCapabilityTier.MAC1_COMPAT
                || capabilityTier == MetalCapabilityTier.UNKNOWN;
        // Also disable Apple's validation layer unless the non-destructive Mac2 feature-set query
        // and family evidence agree. NVIDIA is hard-capped to MacFamily1 even when OCLP reports newer
        // families, so it always takes the validation-off / CPU-indirect compatibility path.
        boolean runtimeProbeUnsafe = metal.available() && !metal.validationSafeByDefault();
        boolean forceMac1Validation = Boolean.parseBoolean(System.getProperty(MAC1_VALIDATION_PROPERTY, "false"));
        boolean effectiveDebug = requestedDebug && (!(compatibilityTier || runtimeProbeUnsafe) || forceMac1Validation);
        if (requestedDebug && !effectiveDebug) {
            System.out.println("[Native Accelerator] Metal validation disabled before device creation: "
                    + "legacy/patched capability path (tier=" + capabilityTier
                    + ", legacyNvidia=" + metal.legacyNvidia()
                    + ", icbSupported=" + metal.indirectCommandBufferSupported() + "). "
                    + "SDL verbose logging remains enabled. Override only for diagnostics with -D"
                    + MAC1_VALIDATION_PROPERTY + "=true");
        }

        long handle;
        try {
            if (MetalInterop.classPresent(MetalInterop.SDL_PROPERTIES)) {
                int props = createDeviceProperties(effectiveDebug, requestedDebug, allowMacFamily1);
                try {
                    handle = MetalInterop.sdlLong("SDL_CreateGPUDeviceWithProperties", props);
                } finally {
                    MetalInterop.propertiesCall("SDL_DestroyProperties", props);
                }
            } else {
                int msl = MetalInterop.sdl("SDL_GPU_SHADERFORMAT_MSL");
                handle = MetalInterop.sdlLong("SDL_CreateGPUDevice", msl, effectiveDebug, DRIVER_NAME);
            }
        } catch (Throwable t) {
            throw new BackendCreationException("SDL3 Metal device creation failed: " + t.getMessage(),
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
        if (handle == 0L) {
            throw new BackendCreationException("SDL3 Metal device creation failed after probe '" + lastProbeReason
                    + "': " + MetalInterop.lastSdlError(),
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
        if (capabilityTier == MetalCapabilityTier.UNKNOWN && allowMacFamily1) {
            // The device creation itself succeeded after an inconclusive strict probe. On an OCLP or
            // otherwise patched host, treating this as Mac1 is safer than touching unsupported ICBs.
            capabilityTier = MetalCapabilityTier.MAC1_COMPAT;
        }
        System.out.println("[Native Accelerator] SDL3 Metal device created successfully; probe=" + lastProbeReason
                + ", allowMacFamily1=" + allowMacFamily1
                + ", capabilityTier=" + capabilityTier);

        try {
            MetalDevice backend = new MetalDevice(handle, options, capabilityTier, metal);
            GpuDevice frontend = new FrontendGpuDevice(backend);
            deviceOpened();
            return frontend;
        } catch (Throwable t) {
            try {
                MetalInterop.sdlCall("SDL_DestroyGPUDevice", handle);
            } catch (Throwable ignored) {
            }
            throw new BackendCreationException("Failed to initialize RenderPearl Metal backend: " + t.getMessage(),
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }
}

