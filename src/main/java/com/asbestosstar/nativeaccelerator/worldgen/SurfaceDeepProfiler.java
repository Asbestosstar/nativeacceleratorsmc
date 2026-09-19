package com.asbestosstar.nativeaccelerator.worldgen;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

/**
 * Sampled thread-local diagnostic profiler for the extremely hot surface-rule evaluator.
 * Exact call counts are retained, but expensive nanoTime/ThreadMXBean reads happen only once per N calls.
 */
public final class SurfaceDeepProfiler {
    private static final int SAMPLE_RATE = Math.max(1,
            NativeAcceleratorConfig.intValue("worldgen.deepProfileSampleRate", 256, 1));
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private SurfaceDeepProfiler() {}

    public static void beginSurface() { STATE.get().reset(); }
    public static void endSurface() {
        State s = STATE.get();
        s.compile.flush("surface.compile");
        s.rule.flush("surface.ruleEvaluation");
        s.biome.flush("surface.ruleBiome");
        s.preliminary.flush("surface.preliminaryLevel");
        s.secondary.flush("surface.secondaryNoise");
        s.reset();
    }

    public static boolean beginCompile() { return WorldgenProfiler.deepEnabled() && STATE.get().compile.begin(); }
    public static void endCompile(boolean sampled) { if (sampled) STATE.get().compile.end(true); }
    public static boolean beginRule() { return WorldgenProfiler.deepEnabled() && STATE.get().rule.begin(); }
    public static void endRule(boolean sampled) { if (sampled) STATE.get().rule.end(true); }
    public static boolean beginBiome() { return WorldgenProfiler.deepEnabled() && STATE.get().biome.begin(); }
    public static void endBiome(boolean sampled) { if (sampled) STATE.get().biome.end(true); }
    public static boolean beginPreliminary() { return WorldgenProfiler.deepEnabled() && STATE.get().preliminary.begin(); }
    public static void endPreliminary(boolean sampled) { if (sampled) STATE.get().preliminary.end(true); }
    public static boolean beginSecondary() { return WorldgenProfiler.deepEnabled() && STATE.get().secondary.begin(); }
    public static void endSecondary(boolean sampled) { if (sampled) STATE.get().secondary.end(true); }

    public static int sampleRate() { return SAMPLE_RATE; }

    private static final class State {
        final Accumulator compile = new Accumulator();
        final Accumulator rule = new Accumulator();
        final Accumulator biome = new Accumulator();
        final Accumulator preliminary = new Accumulator();
        final Accumulator secondary = new Accumulator();
        void reset() { compile.reset(); rule.reset(); biome.reset(); preliminary.reset(); secondary.reset(); }
    }

    private static final class Accumulator {
        long calls, samples, wall, cpu, maxWall, wallStart, cpuStart;
        boolean hasCpu;

        boolean begin() {
            ++calls;
            if (calls % SAMPLE_RATE != 0L) return false;
            wallStart = System.nanoTime();
            cpuStart = WorldgenProfiler.beginCpu();
            return true;
        }
        void end(boolean sampled) {
            if (!sampled) return;
            long elapsed = Math.max(0L, System.nanoTime() - wallStart);
            ++samples;
            wall += elapsed;
            maxWall = Math.max(maxWall, elapsed);
            if (cpuStart >= 0L) {
                long end = WorldgenProfiler.beginCpu();
                if (end >= cpuStart) { cpu += end - cpuStart; hasCpu = true; }
            }
        }
        void flush(String name) {
            if (calls == 0L) return;
            if (samples == 0L) {
                WorldgenProfiler.recordAggregate(name, calls, 0L, -1L, calls, 0L);
                return;
            }
            double scale = (double) calls / (double) samples;
            long estimatedWall = saturatingScale(wall, scale);
            long estimatedCpu = hasCpu ? saturatingScale(cpu, scale) : -1L;
            WorldgenProfiler.recordAggregate(name, calls, estimatedWall, estimatedCpu, calls, maxWall);
            WorldgenProfiler.addUnits(name + ".samples", samples);
        }
        void reset() { calls=samples=wall=cpu=maxWall=wallStart=0L; cpuStart=-1L; hasCpu=false; }
        private static long saturatingScale(long value, double scale) {
            double r = value * scale;
            return r >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, Math.round(r));
        }
    }
}
