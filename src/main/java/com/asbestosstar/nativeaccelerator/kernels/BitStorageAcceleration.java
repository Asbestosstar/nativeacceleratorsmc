package com.asbestosstar.nativeaccelerator.kernels;

/**
 * Eligibility and size policy for the native bit-storage kernels.
 *
 * <p>This class is deliberately Minecraft-free: it takes only {@code long[]}/{@code int[]} shapes, so the
 * same decision logic is unit-testable without the game on the classpath. A Mixin is expected to be the
 * only caller that also links Minecraft.</p>
 *
 * <p>Why a size gate exists at all: {@link NativeKernelBridge} must stage heap arrays through direct
 * buffers, because ordinary Panama downcalls cannot expose movable Java heap storage. That staging cost is
 * paid per call and is independent of the kernel, so for small storages the plain Java loop is faster than
 * the copy + downcall + copy round trip. The gate keeps the native path on the side of the crossover that
 * actually wins; {@link #MIN_ELEMENTS_PROPERTY} exists so the crossover can be re-measured on a new host
 * without a rebuild.</p>
 */
public final class BitStorageAcceleration {

    /**
     * Minimum number of values before the native unpack is attempted. Below this the vanilla Java loop is
     * left in place. Tunable with {@code -Dnativeaccelerator.bits.minElements=N}; {@code 0} forces the
     * native path for every supported size.
     */
    public static final String MIN_ELEMENTS_PROPERTY = "nativeaccelerator.bits.minElements";

    /**
     * Default crossover measured by {@code BitStorageHarness} (run through
     * {@code ./run-bit-storage-harness.sh}) on macos-amd64 with the scalar backend.
     *
     * <p>The harness times the pure Java loop against the full native staging + downcall + kernel path and
     * reports, by majority of independent passes, the first size at which native wins for each width. Across
     * repeated runs no width ever won below 512 elements, but the first-winning size still drifts by up to a
     * factor of two between runs (for example bits=5 was observed first winning at 768 in one run and at
     * 2048 in another) because the measurement includes per-call JVM timer, allocation and JIT noise. The
     * gate is therefore set at the top of the observed band rather than at one run's number: at 2048 the
     * native path is only taken once it has won in every run observed, at the cost of leaving some storages
     * in the 512..2048 range on the Java loop. That trade is deliberate -- a wrongly-taken native path must
     * never cost more than the loop it replaces.</p>
     *
     * <p>These numbers are host- and backend-specific. Re-measure with the harness on a new host instead of
     * assuming the value travels; the property override makes that possible without a rebuild.</p>
     */
    static final int DEFAULT_MIN_ELEMENTS = 2048;

    private BitStorageAcceleration() {}

    /**
     * Native equivalent of {@code SimpleBitStorage.unpack(int[])}.
     *
     * @return {@code true} when {@code output} was fully written by the native kernel and the caller must
     *         skip its own loop; {@code false} when the caller must run the vanilla implementation because
     *         the native backend, the capability, the layout, or the size gate declined the work.
     */
    public static boolean unpackSimple(long[] data, int bits, int size, int[] output) {
        if (!supportsSimpleLayout(bits, size)) return false;
        if (data == null || output == null || output.length < size) return false;
        if (size < minimumElements()) return false;
        return NativeKernelBridge.unpackSimpleBitStorage(data, bits, size, output);
    }

    /**
     * The runtime {@code SimpleBitStorage} layout is only valid for 1..32 bits, which is also the range the
     * native kernel accepts. Checking it here means the kernel can never return its "invalid bits" error,
     * which the Java ABI cannot observe and which would otherwise leave staging-buffer contents in
     * {@code output}.
     */
    static boolean supportsSimpleLayout(int bits, int size) {
        return bits >= 1 && bits <= 32 && size > 0;
    }

    /** Resolved element threshold, never negative. Invalid values fall back to the default. */
    static int minimumElements() {
        String raw = System.getProperty(MIN_ELEMENTS_PROPERTY, "").trim();
        if (raw.isEmpty()) return DEFAULT_MIN_ELEMENTS;
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MIN_ELEMENTS;
        }
    }
}
