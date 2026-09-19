package com.asbestosstar.nativeaccelerator.kernels;

import com.asbestosstar.nativeaccelerator.NativeAccelerator;
import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.nativeapi.Capabilities;
import com.asbestosstar.nativeaccelerator.nativeapi.NativeApi;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * DAX-aware bulk integer operations.
 *
 * <p>Dispatch order in {@code auto} mode is: optional Oracle {@code DaxIntStream} JAR, native
 * {@code libdax}, then a compact Java loop. The JAR is preferred because it already recognizes
 * stream predicates and contains its own profitability/fallback heuristics; the native path remains
 * useful when the JAR is absent and for callers that already own native memory.</p>
 */
public final class DaxIntOps {
    private static final int DEFAULT_NATIVE_MIN_ELEMENTS = 65_536;
    private static final int DEFAULT_JAR_MIN_ELEMENTS = 0;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private DaxIntOps() {}

    /** True when the optional Oracle DaxIntStream class is loadable and has the expected Stream-like API. */
    public static boolean legacyDaxIntStreamPresent() {
        return DaxIntStreamAdapter.available();
    }

    /** Name of the optional DaxIntStream class actually discovered, or the empty string. */
    public static String daxIntStreamClassName() {
        return DaxIntStreamAdapter.className();
    }

    /** True when the runtime policy allows use of the optional DaxIntStream JAR. */
    public static boolean daxIntStreamEligible() {
        Mode mode = mode();
        return mode != Mode.OFF && mode != Mode.NATIVE && DaxIntStreamAdapter.available();
    }

    /** True when the optional native libdax range-scan extension is usable. */
    public static boolean hardwareEligible() {
        Mode mode = mode();
        if (mode == Mode.OFF || mode == Mode.JAR) return false;
        Optional<NativeApi> optional = NativeAccelerator.api();
        if (optional.isEmpty()) return false;
        NativeApi api = optional.get();
        if (!api.daxIntScanAvailable()) return false;
        long caps = api.capabilities();
        if ((caps & Capabilities.DAX_LIBRARY) == 0 || (caps & Capabilities.DAX_INT_SCAN) == 0) return false;
        boolean allowEmulation = NativeAcceleratorConfig.booleanValue("dax.allowEmulation", false);
        return allowEmulation || (caps & Capabilities.DAX_DEVICE) != 0;
    }

    /** Preferred runtime backend before per-call size/profitability checks. */
    public static String preferredBackend() {
        if (daxIntStreamEligible()) return "dax-int-stream";
        if (hardwareEligible()) return "libdax";
        return "java";
    }

    public static long countBetween(int[] values, int lowerInclusive, int upperInclusive) {
        if (values == null || lowerInclusive > upperInclusive) return 0L;
        Long jar = tryJarCountBetween(values, lowerInclusive, upperInclusive);
        if (jar != null) return jar;
        long nativeResult = tryCountBetween(values, lowerInclusive, upperInclusive);
        if (nativeResult >= 0L) return nativeResult;
        long count = 0L;
        for (int value : values) if (value >= lowerInclusive && value <= upperInclusive) count++;
        return count;
    }

    public static boolean anyBetween(int[] values, int lowerInclusive, int upperInclusive) {
        if (values == null || lowerInclusive > upperInclusive) return false;
        Boolean jar = tryJarAnyBetween(values, lowerInclusive, upperInclusive);
        if (jar != null) return jar;
        long nativeResult = tryCountBetween(values, lowerInclusive, upperInclusive);
        if (nativeResult >= 0L) return nativeResult != 0L;
        for (int value : values) if (value >= lowerInclusive && value <= upperInclusive) return true;
        return false;
    }

    public static boolean allBetween(int[] values, int lowerInclusive, int upperInclusive) {
        if (values == null || lowerInclusive > upperInclusive) return false;
        Boolean jar = tryJarAllBetween(values, lowerInclusive, upperInclusive);
        if (jar != null) return jar;
        long nativeResult = tryCountBetween(values, lowerInclusive, upperInclusive);
        if (nativeResult >= 0L) return nativeResult == values.length;
        for (int value : values) if (value < lowerInclusive || value > upperInclusive) return false;
        return true;
    }

    public static boolean noneBetween(int[] values, int lowerInclusive, int upperInclusive) {
        if (values == null || lowerInclusive > upperInclusive) return true;
        Boolean jar = tryJarNoneBetween(values, lowerInclusive, upperInclusive);
        if (jar != null) return jar;
        long nativeResult = tryCountBetween(values, lowerInclusive, upperInclusive);
        if (nativeResult >= 0L) return nativeResult == 0L;
        for (int value : values) if (value >= lowerInclusive && value <= upperInclusive) return false;
        return true;
    }

    public static int[] filterBetween(int[] values, int lowerInclusive, int upperInclusive) {
        if (values == null || lowerInclusive > upperInclusive) return new int[0];
        int[] jar = tryJarFilterBetween(values, lowerInclusive, upperInclusive);
        if (jar != null) return jar;
        int[] nativeResult = tryFilterBetween(values, lowerInclusive, upperInclusive);
        if (nativeResult != null) return nativeResult;
        int count = 0;
        for (int value : values) if (value >= lowerInclusive && value <= upperInclusive) count++;
        int[] out = new int[count];
        int index = 0;
        for (int value : values) if (value >= lowerInclusive && value <= upperInclusive) out[index++] = value;
        return out;
    }

    /** Returns null when the optional DaxIntStream JAR is unavailable, disabled, too small, or failed. */
    private static Long tryJarCountBetween(int[] values, int lowerInclusive, int upperInclusive) {
        if (!jarEligible(values)) return null;
        if (!DaxConcurrencyLimiter.tryEnter()) return null;
        try {
            return DaxIntStreamAdapter.countBetween(values, lowerInclusive, upperInclusive);
        } catch (Throwable failure) {
            DaxIntStreamAdapter.markFailed(failure);
            return null;
        } finally {
            DaxConcurrencyLimiter.exit();
        }
    }

    private static int[] tryJarFilterBetween(int[] values, int lowerInclusive, int upperInclusive) {
        if (!jarEligible(values)) return null;
        if (!DaxConcurrencyLimiter.tryEnter()) return null;
        try {
            return DaxIntStreamAdapter.filterBetween(values, lowerInclusive, upperInclusive);
        } catch (Throwable failure) {
            DaxIntStreamAdapter.markFailed(failure);
            return null;
        } finally {
            DaxConcurrencyLimiter.exit();
        }
    }

    private static Boolean tryJarAnyBetween(int[] values, int lowerInclusive, int upperInclusive) {
        if (!jarEligible(values)) return null;
        if (!DaxConcurrencyLimiter.tryEnter()) return null;
        try {
            return DaxIntStreamAdapter.anyBetween(values, lowerInclusive, upperInclusive);
        } catch (Throwable failure) {
            DaxIntStreamAdapter.markFailed(failure);
            return null;
        } finally {
            DaxConcurrencyLimiter.exit();
        }
    }

    private static Boolean tryJarAllBetween(int[] values, int lowerInclusive, int upperInclusive) {
        if (!jarEligible(values)) return null;
        if (!DaxConcurrencyLimiter.tryEnter()) return null;
        try {
            return DaxIntStreamAdapter.allBetween(values, lowerInclusive, upperInclusive);
        } catch (Throwable failure) {
            DaxIntStreamAdapter.markFailed(failure);
            return null;
        } finally {
            DaxConcurrencyLimiter.exit();
        }
    }

    private static Boolean tryJarNoneBetween(int[] values, int lowerInclusive, int upperInclusive) {
        if (!jarEligible(values)) return null;
        if (!DaxConcurrencyLimiter.tryEnter()) return null;
        try {
            return DaxIntStreamAdapter.noneBetween(values, lowerInclusive, upperInclusive);
        } catch (Throwable failure) {
            DaxIntStreamAdapter.markFailed(failure);
            return null;
        } finally {
            DaxConcurrencyLimiter.exit();
        }
    }

    /** Returns -1 when native libdax is not selected/profitable or the native operation fails. */
    public static long tryCountBetween(int[] values, int lowerInclusive, int upperInclusive) {
        if (!nativeEligible(values, lowerInclusive, upperInclusive)) return -1L;
        if (!DaxConcurrencyLimiter.tryEnter()) return -1L;
        try {
            NativeApi api = NativeAccelerator.api().orElseThrow();
            long bytes = (long) values.length * Integer.BYTES;
            MemorySegment src = SCRATCH.get().first(bytes);
            src.asSlice(0, bytes).copyFrom(MemorySegment.ofArray(values).asSlice(0, bytes));
            return api.daxCountI32Range(src, values.length, lowerInclusive, upperInclusive);
        } catch (Throwable ignored) {
            return -1L;
        } finally {
            DaxConcurrencyLimiter.exit();
        }
    }

    /** Returns null when native libdax is not selected/profitable or the native operation fails. */
    public static int[] tryFilterBetween(int[] values, int lowerInclusive, int upperInclusive) {
        if (!nativeEligible(values, lowerInclusive, upperInclusive)) return null;
        if (!DaxConcurrencyLimiter.tryEnter()) return null;
        try {
            NativeApi api = NativeAccelerator.api().orElseThrow();
            long bytes = (long) values.length * Integer.BYTES;
            Scratch scratch = SCRATCH.get();
            MemorySegment src = scratch.first(bytes);
            MemorySegment dst = scratch.second(bytes);
            src.asSlice(0, bytes).copyFrom(MemorySegment.ofArray(values).asSlice(0, bytes));
            long selected = api.daxSelectI32Range(dst, values.length, src, values.length,
                    lowerInclusive, upperInclusive);
            if (selected < 0 || selected > values.length) return null;
            int[] out = new int[(int) selected];
            long outBytes = selected * Integer.BYTES;
            MemorySegment.ofArray(out).asSlice(0, outBytes).copyFrom(dst.asSlice(0, outBytes));
            return out;
        } catch (Throwable ignored) {
            return null;
        } finally {
            DaxConcurrencyLimiter.exit();
        }
    }

    /** Zero-copy entrypoint for callers that already own native int32 storage. JAR streams cannot use this. */
    public static long tryCountBetweenNative(MemorySegment srcI32, long count,
                                             int lowerInclusive, int upperInclusive) {
        if (srcI32 == null || !srcI32.isNative() || count < nativeMinimumElements() ||
                lowerInclusive > upperInclusive || !hardwareEligible()) return -1L;
        if (!DaxConcurrencyLimiter.tryEnter()) return -1L;
        try {
            return NativeAccelerator.api().orElseThrow()
                    .daxCountI32Range(srcI32, count, lowerInclusive, upperInclusive);
        } catch (Throwable ignored) {
            return -1L;
        } finally {
            DaxConcurrencyLimiter.exit();
        }
    }

    /** Zero-copy select for native buffers. JAR streams cannot use this. */
    public static long trySelectBetweenNative(MemorySegment dstI32, long dstCapacity,
                                              MemorySegment srcI32, long count,
                                              int lowerInclusive, int upperInclusive) {
        if (dstI32 == null || srcI32 == null || !dstI32.isNative() || !srcI32.isNative() ||
                count < nativeMinimumElements() || dstCapacity < 0 || lowerInclusive > upperInclusive ||
                !hardwareEligible()) return -1L;
        if (!DaxConcurrencyLimiter.tryEnter()) return -1L;
        try {
            return NativeAccelerator.api().orElseThrow().daxSelectI32Range(
                    dstI32, dstCapacity, srcI32, count, lowerInclusive, upperInclusive);
        } catch (Throwable ignored) {
            return -1L;
        } finally {
            DaxConcurrencyLimiter.exit();
        }
    }

    private static boolean jarEligible(int[] values) {
        return values != null && values.length >= jarMinimumElements() && daxIntStreamEligible();
    }

    private static boolean nativeEligible(int[] values, int lowerInclusive, int upperInclusive) {
        return values != null && lowerInclusive <= upperInclusive && values.length >= nativeMinimumElements()
                && hardwareEligible();
    }

    private static int jarMinimumElements() {
        // Default 0: Oracle's DaxIntStream backend already contains profitability heuristics and can
        // choose a core implementation when DAX offload is not worthwhile.
        return NativeAcceleratorConfig.intValue("dax.jar.minElements", DEFAULT_JAR_MIN_ELEMENTS, 0);
    }

    private static int nativeMinimumElements() {
        // Native fallback must pay Java heap -> direct staging, so keep the conservative crossover.
        return NativeAcceleratorConfig.intValue("dax.minElements", DEFAULT_NATIVE_MIN_ELEMENTS, 0);
    }

    private static Mode mode() {
        String value = NativeAcceleratorConfig.stringValue("dax.mode", "auto").trim().toLowerCase();
        return switch (value) {
            case "off", "false", "disabled" -> Mode.OFF;
            case "jar", "stream", "daxintstream" -> Mode.JAR;
            case "native", "libdax" -> Mode.NATIVE;
            default -> Mode.AUTO;
        };
    }

    private enum Mode { AUTO, JAR, NATIVE, OFF }

    private static final class Scratch {
        private ByteBuffer first = ByteBuffer.allocateDirect(0);
        private ByteBuffer second = ByteBuffer.allocateDirect(0);

        MemorySegment first(long bytes) { first = ensure(first, bytes); return MemorySegment.ofBuffer(first); }
        MemorySegment second(long bytes) { second = ensure(second, bytes); return MemorySegment.ofBuffer(second); }

        private static ByteBuffer ensure(ByteBuffer existing, long requested) {
            if (requested > Integer.MAX_VALUE) throw new IllegalArgumentException("DAX staging buffer too large");
            int bytes = (int) requested;
            if (existing.capacity() >= bytes) { existing.clear(); return existing; }
            int capacity = 1;
            while (capacity < bytes && capacity > 0) capacity <<= 1;
            if (capacity <= 0) capacity = bytes;
            return ByteBuffer.allocateDirect(capacity);
        }
    }
}
