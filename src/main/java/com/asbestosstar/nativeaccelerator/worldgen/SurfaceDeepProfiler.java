package com.asbestosstar.nativeaccelerator.worldgen;

/**
 * Thread-local aggregation for diagnostic surface-rule timings.
 *
 * <p>Hot rule/context helpers may execute tens of thousands of times per chunk. Updating the global
 * ConcurrentHashMap/LongAdders on every call would materially perturb the measurement, so these counters
 * stay local to the worldgen worker and flush once at the end of MaterialSystem.buildSurface.</p>
 */
public final class SurfaceDeepProfiler {
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private SurfaceDeepProfiler() {}

    public static void beginSurface() {
        STATE.get().reset();
    }

    public static void endSurface() {
        State state = STATE.get();
        state.compile.flush("surface.compile");
        state.rule.flush("surface.ruleEvaluation");
        state.biome.flush("surface.ruleBiome");
        state.preliminary.flush("surface.preliminaryLevel");
        state.secondary.flush("surface.secondaryNoise");
        state.reset();
    }

    public static void addCompile(long wallNanos, long cpuNanos) {
        STATE.get().compile.add(wallNanos, cpuNanos);
    }

    public static void addRule(long wallNanos, long cpuNanos) {
        STATE.get().rule.add(wallNanos, cpuNanos);
    }

    public static void addBiome(long wallNanos, long cpuNanos) {
        STATE.get().biome.add(wallNanos, cpuNanos);
    }

    public static void addPreliminary(long wallNanos, long cpuNanos) {
        STATE.get().preliminary.add(wallNanos, cpuNanos);
    }

    public static void addSecondary(long wallNanos, long cpuNanos) {
        STATE.get().secondary.add(wallNanos, cpuNanos);
    }

    private static final class State {
        final Accumulator compile = new Accumulator();
        final Accumulator rule = new Accumulator();
        final Accumulator biome = new Accumulator();
        final Accumulator preliminary = new Accumulator();
        final Accumulator secondary = new Accumulator();

        void reset() {
            compile.reset();
            rule.reset();
            biome.reset();
            preliminary.reset();
            secondary.reset();
        }
    }

    private static final class Accumulator {
        long calls;
        long wall;
        long cpu;
        long maxWall;
        boolean hasCpu;

        void add(long wallNanos, long cpuNanos) {
            long safeWall = Math.max(0L, wallNanos);
            ++calls;
            wall += safeWall;
            maxWall = Math.max(maxWall, safeWall);
            if (cpuNanos >= 0L) {
                cpu += cpuNanos;
                hasCpu = true;
            }
        }

        void flush(String name) {
            if (calls == 0L) return;
            WorldgenProfiler.recordAggregate(name, calls, wall, hasCpu ? cpu : -1L, calls, maxWall);
        }

        void reset() {
            calls = 0L;
            wall = 0L;
            cpu = 0L;
            maxWall = 0L;
            hasCpu = false;
        }
    }
}
