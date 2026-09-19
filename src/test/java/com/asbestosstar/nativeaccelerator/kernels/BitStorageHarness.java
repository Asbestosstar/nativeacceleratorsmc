package com.asbestosstar.nativeaccelerator.kernels;

import com.asbestosstar.nativeaccelerator.NativeAccelerator;
import com.asbestosstar.nativeaccelerator.nativeapi.Capabilities;
import com.asbestosstar.nativeaccelerator.nativeapi.NativeApi;

import java.util.Arrays;
import java.util.Optional;
import java.util.Random;

/**
 * BitStorageHarness -- differential correctness and crossover measurement for the {@code SimpleBitStorage}
 * native fast path.
 *
 * <p>Plain-main harness (this repository has no JUnit in its local repository). It exists to substantiate
 * two things that were previously only asserted in comments:</p>
 *
 * <ol>
 *   <li><b>Differential correctness.</b> For every supported bit width and a spread of sizes, the output of
 *       {@link NativeKernelBridge#unpackSimpleBitStorage} is compared against a byte-for-byte
 *       reimplementation of the vanilla {@code net.minecraft.util.SimpleBitStorage.unpack(int[])} loop. The
 *       vanilla behaviour below was transcribed from the disassembled 26.3 client class, so this check is
 *       what actually protects the layout equivalence the Mixin depends on.</li>
 *   <li><b>Crossover measurement.</b> {@link BitStorageAcceleration#DEFAULT_MIN_ELEMENTS} is a size gate that
 *       only earns its keep if it sits on the winning side of the staging-copy + downcall crossover. This
 *       harness times the pure Java loop against the full native path across sizes and reports the smallest
 *       size at which native wins. A single pass is noisy -- the first-winning size drifts by a few hundred
 *       elements between runs -- so the per-size verdict is a median over several independent passes and the
 *       reported crossover is the worst width/best-of-pass case.</li>
 * </ol>
 *
 * <p>Run it through {@code ./run-bit-storage-harness.sh}.</p>
 */
public final class BitStorageHarness {

    /** Typical runtime widths: 4 = light, 5 = legacy block states, 9 = modern paletted block states. */
    private static final int[] BENCH_BITS = {4, 5, 9};

    private static final int[] BENCH_SIZES = {64, 128, 256, 512, 768, 1024, 1536, 2048, 3072, 4096, 8192, 16384, 65536, 262144};

    /** Independent timing passes; the per-size winner is decided by majority so JIT/timer noise cannot flip it. */
    private static final int PASSES = 5;

    /** Written but never read for its value; keeps the JIT from eliminating the timed loops. */
    private static long sink;

    private static int checks;
    private static int failures;

    private BitStorageHarness() {}

    public static void main(String[] args) {
        System.out.println("[bits] BitStorageHarness -- SimpleBitStorage native fast path");

        NativeAccelerator.initialize();
        Optional<NativeApi> optional = NativeAccelerator.api();
        if (optional.isEmpty()) {
            System.out.println("[bits] SKIP: native backend unavailable, Java path only.");
            return;
        }
        NativeApi api = optional.get();
        if ((api.capabilities() & Capabilities.PACKED_BITS) == 0) {
            System.out.println("[bits] SKIP: backend lacks PACKED_BITS.");
            return;
        }
        System.out.println("[bits] backend=" + api.backendName());

        differentialSuite();
        gateBehaviour();
        benchmark();

        System.out.println("[bits] checks=" + checks + " failures=" + failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ---- differential correctness -------------------------------------------------------------

    private static void differentialSuite() {
        System.out.println("[bits] differential correctness: native kernel vs vanilla unpack loop");
        int[] sizes = {1, 2, 3, 7, 13, 63, 64, 65, 255, 256, 257, 1000, 1024, 4096, 4097, 20000};
        for (int bits = 1; bits <= 32; bits++) {
            for (int size : sizes) {
                differential(bits, size, 0x5DEECE66DL ^ (bits * 1000003L) ^ size);
            }
        }
        System.out.println("[bits] differential done (" + checks + " cases, " + failures + " failures)");
    }

    private static void differential(int bits, int size, long seed) {
        int[] values = randomValues(bits, size, seed);
        long[] packed = packSimple(values, bits, size);

        int[] expected = new int[size];
        referenceUnpack(packed, bits, size, expected);
        check(Arrays.equals(values, expected), "packer/reference round-trip bits=" + bits + " size=" + size);

        int[] actual = new int[size];
        boolean accelerated = NativeKernelBridge.unpackSimpleBitStorage(packed, bits, size, actual);
        check(accelerated, "native declined a supported layout bits=" + bits + " size=" + size);
        check(Arrays.equals(expected, actual),
                "native output differs from vanilla bits=" + bits + " size=" + size);
    }

    // ---- size gate ---------------------------------------------------------------------------

    private static void gateBehaviour() {
        System.out.println("[bits] size gate behaviour");
        int size = 4096;
        long[] packed = packSimple(randomValues(5, size, 99L), 5, size);
        int[] out = new int[size];

        check(!BitStorageAcceleration.supportsSimpleLayout(0, size), "bits=0 must be rejected");
        check(!BitStorageAcceleration.supportsSimpleLayout(33, size), "bits=33 must be rejected");
        check(!BitStorageAcceleration.supportsSimpleLayout(5, 0), "size=0 must be rejected");
        check(!BitStorageAcceleration.unpackSimple(packed, 0, size, out), "bits=0 must never reach the kernel");
        check(!BitStorageAcceleration.unpackSimple(packed, 33, size, out), "bits=33 must never reach the kernel");
        check(!BitStorageAcceleration.unpackSimple(packed, 5, 0, out), "size=0 must never reach the kernel");

        String key = BitStorageAcceleration.MIN_ELEMENTS_PROPERTY;
        String saved = System.getProperty(key);
        try {
            System.setProperty(key, "0");
            check(BitStorageAcceleration.minimumElements() == 0, "minElements=0 override");
            check(BitStorageAcceleration.unpackSimple(packed, 5, 1024, out), "gate=0 must accelerate size 1024");

            System.setProperty(key, "-5");
            check(BitStorageAcceleration.minimumElements() == 0, "negative minElements clamps to 0");

            System.setProperty(key, "not-a-number");
            check(BitStorageAcceleration.minimumElements() == BitStorageAcceleration.DEFAULT_MIN_ELEMENTS,
                    "non-numeric minElements falls back to default");

            System.setProperty(key, "");
            check(BitStorageAcceleration.minimumElements() == BitStorageAcceleration.DEFAULT_MIN_ELEMENTS,
                    "empty minElements falls back to default");

            System.setProperty(key, String.valueOf(size + 1));
            check(!BitStorageAcceleration.unpackSimple(packed, 5, size, out), "size below gate must stay on Java");

            System.setProperty(key, String.valueOf(size));
            check(BitStorageAcceleration.unpackSimple(packed, 5, size, out), "size at the gate must accelerate");
        } finally {
            if (saved == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, saved);
            }
        }
        System.out.println("[bits] gate checks done (" + checks + " total, " + failures + " failures)");
    }

    // ---- crossover benchmark ------------------------------------------------------------------

    private static void benchmark() {
        System.out.println("[bits] crossover benchmark: Java loop vs native staging+downcall+kernel");
        System.out.println("[bits] each pass is timed independently; a size counts as native only on a majority of passes");
        System.out.printf("%-6s %-8s %14s %14s %8s%n", "bits", "size", "java ns/call", "native ns/call", "winner");

        int worstCrossover = 0;
        for (int bits : BENCH_BITS) {
            int crossover = -1;
            for (int size : BENCH_SIZES) {
                long[] packed = packSimple(randomValues(bits, size, 4242L + size), bits, size);
                int[] out = new int[size];
                double javaNs = 0.0;
                double nativeNs = 0.0;
                int nativeWins = 0;
                for (int pass = 0; pass < PASSES; pass++) {
                    double j = javaNsPerCall(bits, size, packed, out);
                    double n = nativeNsPerCall(bits, size, packed, out);
                    javaNs += j;
                    nativeNs += n;
                    if (n < j) nativeWins++;
                }
                javaNs /= PASSES;
                nativeNs /= PASSES;
                boolean nativeWinsMajority = nativeWins * 2 > PASSES;
                if (nativeWinsMajority && crossover < 0) {
                    crossover = size;
                }
                System.out.printf("%-6d %-8d %14.1f %14.1f %8s (%d/%d)%n",
                        bits, size, javaNs, nativeNs, nativeWinsMajority ? "native" : "java", nativeWins, PASSES);
            }
            System.out.println("[bits] bits=" + bits + " first size where native wins: "
                    + (crossover < 0 ? "none in range" : Integer.toString(crossover)));
            if (crossover > worstCrossover) {
                worstCrossover = crossover;
            }
        }

        if (worstCrossover <= 0) {
            System.out.println("[bits] native never beat the Java loop in range; keep the gate conservative.");
            return;
        }
        int suggestion = nextPowerOfTwo(Math.max(256, worstCrossover));
        int current = BitStorageAcceleration.DEFAULT_MIN_ELEMENTS;
        System.out.println("[bits] worst-width crossover (median of " + PASSES + " passes): " + worstCrossover);
        System.out.println("[bits] suggested DEFAULT_MIN_ELEMENTS: " + suggestion + " (current " + current + ")");
        if (suggestion > current) {
            System.out.println("[bits] NOTE: current gate is BELOW the suggested value; some storages may take the"
                    + " slower native path. Consider raising it or re-measuring on this host.");
        } else {
            System.out.println("[bits] current gate is at or above the suggested value on this host.");
        }
    }

    private static double javaNsPerCall(int bits, int size, long[] packed, int[] out) {
        int reps = Math.max(64, 1_000_000 / size);
        for (int i = 0; i < reps; i++) {
            referenceUnpack(packed, bits, size, out);
        }
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 7; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < reps; i++) {
                referenceUnpack(packed, bits, size, out);
            }
            best = Math.min(best, System.nanoTime() - start);
        }
        sink ^= out[size - 1];
        return (double) best / reps;
    }

    private static double nativeNsPerCall(int bits, int size, long[] packed, int[] out) {
        int reps = Math.max(64, 1_000_000 / size);
        for (int i = 0; i < reps; i++) {
            if (!NativeKernelBridge.unpackSimpleBitStorage(packed, bits, size, out)) {
                return Double.NaN;
            }
        }
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 7; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < reps; i++) {
                NativeKernelBridge.unpackSimpleBitStorage(packed, bits, size, out);
            }
            best = Math.min(best, System.nanoTime() - start);
        }
        sink ^= out[size - 1];
        return (double) best / reps;
    }

    // ---- vanilla-equivalent reference ----------------------------------------------------------

    /**
     * Byte-for-byte transcription of {@code SimpleBitStorage.unpack(int[])} from the 26.3 client jar: every
     * cell but the last yields {@code 64 / bits} complete values, and the final cell yields the remainder.
     */
    private static void referenceUnpack(long[] data, int bits, int size, int[] out) {
        int valuesPerLong = 64 / bits;
        long mask = (bits >= 64) ? -1L : (1L << bits) - 1L;
        int index = 0;
        for (int cell = 0; cell < data.length - 1; cell++) {
            long value = data[cell];
            for (int i = 0; i < valuesPerLong; i++) {
                out[index++] = (int) (value & mask);
                value >>>= bits;
            }
        }
        int remaining = size - index;
        if (remaining > 0) {
            long value = data[data.length - 1];
            for (int i = 0; i < remaining; i++) {
                out[index++] = (int) (value & mask);
                value >>>= bits;
            }
        }
    }

    private static long[] packSimple(int[] values, int bits, int size) {
        int valuesPerLong = 64 / bits;
        int cells = (size + valuesPerLong - 1) / valuesPerLong;
        long[] data = new long[cells];
        // A long mask is essential: at bits=32 a set sign bit would otherwise sign-extend into the
        // neighbouring value's slot and make the packer itself look wrong.
        long mask = (bits >= 64) ? -1L : (1L << bits) - 1L;
        for (int i = 0; i < size; i++) {
            int cell = i / valuesPerLong;
            int within = i - cell * valuesPerLong;
            data[cell] |= (((long) values[i]) & mask) << (within * bits);
        }
        return data;
    }

    private static int[] randomValues(int bits, int size, long seed) {
        Random random = new Random(seed);
        int mask = bits >= 32 ? -1 : (1 << bits) - 1;
        int[] values = new int[size];
        for (int i = 0; i < size; i++) {
            values[i] = random.nextInt() & mask;
        }
        return values;
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  FAIL: " + message);
        }
    }
}

