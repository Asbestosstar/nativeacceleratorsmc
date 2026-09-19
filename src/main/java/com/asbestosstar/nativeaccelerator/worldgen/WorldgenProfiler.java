package com.asbestosstar.nativeaccelerator.worldgen;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Low-overhead opt-in world-generation profiler used by the NativeServer0 cold-region benchmark.
 * Timings are summed task wall-times and may overlap when several worldgen workers execute in parallel.
 * Profiling is disabled by default; mixins avoid ThreadLocal/timer mutation entirely in that mode.
 */
public final class WorldgenProfiler {
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("worldgen.profile", false);
    private static final int REPORT_EVERY_FULL_CHUNKS =
            NativeAcceleratorConfig.intValue("worldgen.profileReportEvery", 256, 0);
    private static final ConcurrentHashMap<String, Metric> METRICS = new ConcurrentHashMap<>();
    private static final AtomicLong FULL_COMPLETIONS = new AtomicLong();
    private static final AtomicLong LAST_REPORTED_FULL = new AtomicLong();

    private WorldgenProfiler() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static long begin() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void recordPhase(String name, long startNanos) {
        recordPhase(name, startNanos, 1L);
    }

    public static void recordPhase(String name, long startNanos, long units) {
        if (!ENABLED || startNanos == 0L) return;
        record(name, System.nanoTime() - startNanos, units, true);
    }

    public static void recordStatus(String status, long startNanos, boolean success) {
        if (!ENABLED || startNanos == 0L) return;
        record("status." + status, System.nanoTime() - startNanos, 1L, success);
        if (success && "full".equals(status)) {
            long full = FULL_COMPLETIONS.incrementAndGet();
            maybePrint(full);
        }
    }

    private static void record(String name, long elapsed, long units, boolean success) {
        Metric metric = METRICS.computeIfAbsent(name, ignored -> new Metric());
        metric.calls.increment();
        metric.nanos.add(Math.max(0L, elapsed));
        metric.units.add(Math.max(0L, units));
        if (!success) metric.failures.increment();
        metric.max.accumulateAndGet(Math.max(0L, elapsed), Math::max);
        metric.threads.add(Thread.currentThread().getName());
    }

    private static void maybePrint(long full) {
        if (REPORT_EVERY_FULL_CHUNKS <= 0 || full % REPORT_EVERY_FULL_CHUNKS != 0) return;
        long previous = LAST_REPORTED_FULL.get();
        if (previous >= full || !LAST_REPORTED_FULL.compareAndSet(previous, full)) return;
        printReport("checkpoint fullChunks=" + full);
    }

    public static void printFinalReport() {
        if (ENABLED && !METRICS.isEmpty()) printReport("final fullChunks=" + FULL_COMPLETIONS.get());
    }

    public static synchronized void printReport(String reason) {
        if (!ENABLED) return;
        ArrayList<Map.Entry<String, Metric>> entries = new ArrayList<>(METRICS.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, Metric> e) -> e.getValue().nanos.sum()).reversed());
        System.out.println("[Native Accelerator] worldgen profile (" + reason + ")");
        System.out.println("  summed times overlap across parallel workers; units are chunks for status.* and cells for density.sampleVolume");
        System.out.printf(Locale.ROOT, "  %-30s %9s %12s %12s %12s %14s %s%n",
                "phase", "calls", "total ms", "avg ms", "max ms", "units", "threads");
        for (Map.Entry<String, Metric> entry : entries) {
            Metric m = entry.getValue();
            long calls = m.calls.sum();
            long total = m.nanos.sum();
            long failures = m.failures.sum();
            String name = entry.getKey() + (failures == 0 ? "" : " !fail=" + failures);
            System.out.printf(Locale.ROOT, "  %-30s %9d %12.3f %12.3f %12.3f %14d %s%n",
                    name, calls, total / 1_000_000.0,
                    calls == 0 ? 0.0 : total / 1_000_000.0 / calls,
                    m.max.get() / 1_000_000.0, m.units.sum(), String.join(",", m.threads));
        }
    }

    private static final class Metric {
        final LongAdder calls = new LongAdder();
        final LongAdder nanos = new LongAdder();
        final LongAdder units = new LongAdder();
        final LongAdder failures = new LongAdder();
        final AtomicLong max = new AtomicLong();
        final Set<String> threads = ConcurrentHashMap.newKeySet();
    }
}
