package com.asbestosstar.nativeaccelerator.worldgen;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
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
 *
 * <p>Elapsed timings are summed task wall-times and may overlap when several worldgen workers execute in
 * parallel. Optional current-thread CPU timing is recorded only for synchronous spans whose start/end run
 * on the same worker. Future/status timings intentionally report no CPU value because their completion may
 * occur on another thread.</p>
 *
 * <p>Profiling is disabled by default; mixins avoid ThreadLocal/timer mutation entirely in that mode.
 * Expensive per-operation profiling is separately gated by {@code worldgen.deepProfile} and its mixins are
 * vetoed before transformation unless that property is explicitly enabled.</p>
 */
public final class WorldgenProfiler {
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("worldgen.profile", false);
    private static final boolean DEEP_ENABLED = ENABLED
            && NativeAcceleratorConfig.booleanValue("worldgen.deepProfile", false);
    private static final boolean CPU_REQUESTED = ENABLED
            && NativeAcceleratorConfig.booleanValue("worldgen.cpuProfiler", false);
    private static final ThreadMXBean THREAD_MX = CPU_REQUESTED ? ManagementFactory.getThreadMXBean() : null;
    private static final boolean CPU_ENABLED = cpuTimingAvailable();
    private static final int REPORT_EVERY_FULL_CHUNKS =
            NativeAcceleratorConfig.intValue("worldgen.profileReportEvery", 256, 0);
    private static final ConcurrentHashMap<String, Metric> METRICS = new ConcurrentHashMap<>();
    private static final AtomicLong FULL_COMPLETIONS = new AtomicLong();
    private static final AtomicLong LAST_REPORTED_FULL = new AtomicLong();

    private WorldgenProfiler() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean deepEnabled() {
        return DEEP_ENABLED;
    }

    public static boolean cpuEnabled() {
        return CPU_ENABLED;
    }

    public static long begin() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** Current-thread CPU timestamp, or {@code -1} when CPU profiling is disabled/unavailable. */
    public static long beginCpu() {
        if (!CPU_ENABLED) return -1L;
        try {
            return THREAD_MX.getCurrentThreadCpuTime();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    public static void recordPhase(String name, long startNanos) {
        recordPhase(name, startNanos, 1L);
    }

    public static void recordPhase(String name, long startNanos, long units) {
        if (!ENABLED || startNanos == 0L) return;
        record(name, System.nanoTime() - startNanos, -1L, units, true);
    }

    /** Record a synchronous span with both wall and current-thread CPU timestamps. */
    public static void recordPhaseCpu(String name, long startNanos, long startCpuNanos) {
        recordPhaseCpu(name, startNanos, startCpuNanos, 1L);
    }

    /** Record a synchronous span with both wall and current-thread CPU timestamps. */
    public static void recordPhaseCpu(String name, long startNanos, long startCpuNanos, long units) {
        if (!ENABLED || startNanos == 0L) return;
        long cpu = -1L;
        if (CPU_ENABLED && startCpuNanos >= 0L) {
            long endCpu = beginCpu();
            if (endCpu >= startCpuNanos) cpu = endCpu - startCpuNanos;
        }
        record(name, System.nanoTime() - startNanos, cpu, units, true);
    }

    /** Record a precomputed synchronous duration; useful around redirected calls. */
    public static void recordDurationCpu(String name, long elapsedNanos, long cpuNanos, long units) {
        if (!ENABLED) return;
        record(name, elapsedNanos, cpuNanos, units, true);
    }

    /**
     * Flush an already aggregated hot-loop metric without paying a concurrent-map update per operation.
     * maxElapsedNanos is the largest single operation observed by the local accumulator.
     */
    public static void recordAggregate(String name, long calls, long elapsedNanos, long cpuNanos,
                                       long units, long maxElapsedNanos) {
        if (!ENABLED || calls <= 0L) return;
        Metric metric = METRICS.computeIfAbsent(name, ignored -> new Metric());
        metric.calls.add(calls);
        metric.nanos.add(Math.max(0L, elapsedNanos));
        if (cpuNanos >= 0L) metric.cpuNanos.add(cpuNanos);
        metric.units.add(Math.max(0L, units));
        metric.max.accumulateAndGet(Math.max(0L, maxElapsedNanos), Math::max);
        metric.threads.add(Thread.currentThread().getName());
    }

    public static void recordStatus(String status, long startNanos, boolean success) {
        if (!ENABLED || startNanos == 0L) return;
        record("status." + status, System.nanoTime() - startNanos, -1L, 1L, success);
        if (success && "full".equals(status)) {
            long full = FULL_COMPLETIONS.incrementAndGet();
            maybePrint(full);
        }
    }

    public static void addUnits(String name, long units) {
        if (!ENABLED || units <= 0L) return;
        METRICS.computeIfAbsent(name, ignored -> new Metric()).units.add(units);
    }

    private static void record(String name, long elapsed, long cpu, long units, boolean success) {
        Metric metric = METRICS.computeIfAbsent(name, ignored -> new Metric());
        long safeElapsed = Math.max(0L, elapsed);
        metric.calls.increment();
        metric.nanos.add(safeElapsed);
        if (cpu >= 0L) metric.cpuNanos.add(cpu);
        metric.units.add(Math.max(0L, units));
        if (!success) metric.failures.increment();
        metric.max.accumulateAndGet(safeElapsed, Math::max);
        if (cpu >= 0L) metric.maxCpu.accumulateAndGet(cpu, Math::max);
        metric.threads.add(Thread.currentThread().getName());
    }

    private static boolean cpuTimingAvailable() {
        if (!CPU_REQUESTED) return false;
        try {
            if (THREAD_MX == null || !THREAD_MX.isCurrentThreadCpuTimeSupported()) return false;
            if (!THREAD_MX.isThreadCpuTimeEnabled()) {
                try {
                    THREAD_MX.setThreadCpuTimeEnabled(true);
                } catch (Throwable ignored) {
                    return false;
                }
            }
            return THREAD_MX.getCurrentThreadCpuTime() >= 0L;
        } catch (Throwable ignored) {
            return false;
        }
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
        System.out.println("  summed elapsed times overlap across parallel workers; CPU is current-thread CPU only for synchronous spans");
        System.out.println("  cpuProfiler=" + CPU_ENABLED + " deepProfile=" + DEEP_ENABLED
                + "; units are chunks for status.* and cells/operations where explicitly supplied");
        System.out.printf(Locale.ROOT,
                "  %-34s %9s %12s %11s %12s %11s %12s %14s %s%n",
                "phase", "calls", "elapsed ms", "avg ms", "cpu ms", "cpu avg", "max ms", "units", "threads");
        for (Map.Entry<String, Metric> entry : entries) {
            Metric m = entry.getValue();
            long calls = m.calls.sum();
            long total = m.nanos.sum();
            long cpu = m.cpuNanos.sum();
            long failures = m.failures.sum();
            String name = entry.getKey() + (failures == 0 ? "" : " !fail=" + failures);
            System.out.printf(Locale.ROOT,
                    "  %-34s %9d %12.3f %11.3f %12.3f %11.3f %12.3f %14d %s%n",
                    name, calls,
                    total / 1_000_000.0,
                    calls == 0 ? 0.0 : total / 1_000_000.0 / calls,
                    cpu / 1_000_000.0,
                    calls == 0 ? 0.0 : cpu / 1_000_000.0 / calls,
                    m.max.get() / 1_000_000.0,
                    m.units.sum(), String.join(",", m.threads));
        }
    }

    private static final class Metric {
        final LongAdder calls = new LongAdder();
        final LongAdder nanos = new LongAdder();
        final LongAdder cpuNanos = new LongAdder();
        final LongAdder units = new LongAdder();
        final LongAdder failures = new LongAdder();
        final AtomicLong max = new AtomicLong();
        final AtomicLong maxCpu = new AtomicLong();
        final Set<String> threads = ConcurrentHashMap.newKeySet();
    }
}
