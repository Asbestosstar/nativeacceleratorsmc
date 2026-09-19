package com.asbestosstar.nativeaccelerator.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Low-overhead aggregate profiler for the model/resource preparation pipeline.
 *
 * <p>Unlike StartupTimer, this deliberately accumulates repeated worker operations.  It is designed for
 * hot asynchronous paths where thousands of calls overlap and a first-occurrence wall-clock timer would
 * hide the actual work distribution.</p>
 */
public final class ModelPipelineProfiler {
    private static final ConcurrentHashMap<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static final boolean ENABLED = com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("model.profiler", false);
    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final boolean THREAD_CPU_SUPPORTED = ENABLED && initializeThreadCpu();

    private ModelPipelineProfiler() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void reset() {
        COUNTERS.clear();
    }

    public static long start() {
        return enabled() ? System.nanoTime() : 0L;
    }

    /** Returns current-thread CPU time for diagnostic separation of execution from scheduler stalls. */
    public static long startThreadCpu() {
        if (!enabled() || !THREAD_CPU_SUPPORTED) return 0L;
        long value = THREAD_MX.getCurrentThreadCpuTime();
        return value < 0L ? 0L : value;
    }

    public static void endThreadCpu(String stage, long startedCpuNanos) {
        endThreadCpu(stage, startedCpuNanos, 1L);
    }

    public static void endThreadCpu(String stage, long startedCpuNanos, long operations) {
        if (startedCpuNanos == 0L || !THREAD_CPU_SUPPORTED) return;
        long ended = THREAD_MX.getCurrentThreadCpuTime();
        if (ended < startedCpuNanos) return;
        record(stage + ".thread-cpu", ended - startedCpuNanos, operations);
    }

    public static void end(String stage, long startedNanos) {
        if (startedNanos == 0L) return;
        record(stage, System.nanoTime() - startedNanos, 1L);
    }

    public static void record(String stage, long nanos) {
        record(stage, nanos, 1L);
    }

    public static void record(String stage, long nanos, long operations) {
        if (!enabled() || stage == null || stage.isBlank() || nanos < 0L) return;
        Counter counter = COUNTERS.computeIfAbsent(stage, ignored -> new Counter());
        counter.nanos.add(nanos);
        counter.calls.increment();
        if (operations > 0L) counter.operations.add(operations);
    }

    public static void addCount(String stage, long operations) {
        if (!enabled() || stage == null || stage.isBlank() || operations <= 0L) return;
        Counter counter = COUNTERS.computeIfAbsent(stage, ignored -> new Counter());
        counter.operations.add(operations);
    }

    public static String report() {
        if (!enabled()) return "";
        List<Row> rows = new ArrayList<>();
        COUNTERS.forEach((name, counter) -> rows.add(new Row(
                name, counter.nanos.sum(), counter.calls.sum(), counter.operations.sum())));
        rows.sort(Comparator.comparingLong(Row::nanos).reversed());
        StringBuilder out = new StringBuilder(1024);
        out.append("[Native Accelerator] model pipeline profiler\n");
        for (Row row : rows) {
            double millis = row.nanos() / 1_000_000.0;
            double usPerOp = row.operations() > 0L ? row.nanos() / 1_000.0 / row.operations() : 0.0;
            out.append(String.format(java.util.Locale.ROOT,
                    "  %-42s %9.2f ms  calls=%6d  ops=%7d  us/op=%8.2f%n",
                    row.name(), millis, row.calls(), row.operations(), usPerOp));
        }
        return out.toString();
    }


    private static boolean initializeThreadCpu() {
        try {
            if (!THREAD_MX.isCurrentThreadCpuTimeSupported()) return false;
            if (!THREAD_MX.isThreadCpuTimeEnabled()) THREAD_MX.setThreadCpuTimeEnabled(true);
            return THREAD_MX.isThreadCpuTimeEnabled();
        } catch (SecurityException | UnsupportedOperationException ignored) {
            return false;
        }
    }

    private static final class Counter {
        final LongAdder nanos = new LongAdder();
        final LongAdder calls = new LongAdder();
        final LongAdder operations = new LongAdder();
    }

    private record Row(String name, long nanos, long calls, long operations) {}
}
