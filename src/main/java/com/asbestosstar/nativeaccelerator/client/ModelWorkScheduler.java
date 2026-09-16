package com.asbestosstar.nativeaccelerator.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Persistent work-stealing scheduler for model preparation.
 *
 * <p>The critical detail is that a whole model family is represented by one external task, then recursively
 * split into lightweight {@link java.util.concurrent.ForkJoinTask}s. Raw models and blockstates therefore
 * share one CPU-wide pool and can steal cores from each other. This avoids both vanilla's thousands of
 * CompletableFutures and pass-1's long-lived batches that could leave an expensive tail on one worker.</p>
 */
public final class ModelWorkScheduler {
    private static final int AVAILABLE_PROCESSORS = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ForkJoinPool DEDICATED_POOL = createPool();

    private ModelWorkScheduler() {}

    public static int parallelism() {
        int configured = NativeAcceleratorConfig.intValue("model.workers", 0, 0);
        return configured > 0 ? configured : AVAILABLE_PROCESSORS;
    }

    public static Executor executor(Executor minecraftExecutor) {
        return NativeAcceleratorConfig.booleanValue("model.dedicatedPool", true)
                ? DEDICATED_POOL
                : minecraftExecutor;
    }

    public static <T> CompletableFuture<List<T>> mapIndexed(
            int itemCount,
            Executor minecraftExecutor,
            IntFunction<T> operation,
            String profilerPrefix) {
        if (itemCount == 0) return CompletableFuture.completedFuture(List.of());
        if (NativeAcceleratorConfig.booleanValue("model.dedicatedPool", true)) {
            return forkJoinMap(itemCount, operation, profilerPrefix);
        }
        return executorMap(itemCount, minecraftExecutor, operation, profilerPrefix);
    }

    /** Parallel side-effect traversal without allocating a result array. */
    public static CompletableFuture<Void> runIndexed(
            int itemCount,
            Executor minecraftExecutor,
            IntConsumer operation,
            String profilerPrefix) {
        if (itemCount == 0) return CompletableFuture.completedFuture(null);
        if (NativeAcceleratorConfig.booleanValue("model.dedicatedPool", true)) {
            int grain = forkJoinGrain(itemCount, profilerPrefix);
            long wallStarted = ModelPipelineProfiler.start();
            ModelPipelineProfiler.addCount(profilerPrefix + ".external-tasks", 1);
            ModelPipelineProfiler.addCount(profilerPrefix + ".grain", grain);
            return CompletableFuture.runAsync(() -> {
                DEDICATED_POOL.invoke(new ConsumerRangeAction(0, itemCount, grain, operation, profilerPrefix));
                ModelPipelineProfiler.end(profilerPrefix + ".workers.wall", wallStarted);
            }, DEDICATED_POOL);
        }

        int workers = Math.min(itemCount, Math.max(1, parallelism()));
        int chunk = Math.max(1, NativeAcceleratorConfig.intValue("model.dynamicChunkSize", 2, 1));
        AtomicInteger cursor = new AtomicInteger();
        ArrayList<CompletableFuture<Void>> futures = new ArrayList<>(workers);
        long wallStarted = ModelPipelineProfiler.start();
        for (int worker = 0; worker < workers; worker++) {
            futures.add(CompletableFuture.runAsync(() -> {
                while (true) {
                    int from = cursor.getAndAdd(chunk);
                    if (from >= itemCount) break;
                    int to = Math.min(itemCount, from + chunk);
                    for (int i = from; i < to; i++) operation.accept(i);
                }
            }, minecraftExecutor));
        }
        ModelPipelineProfiler.addCount(profilerPrefix + ".external-tasks", workers);
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) ->
                ModelPipelineProfiler.end(profilerPrefix + ".workers.wall", wallStarted));
    }

    private static <T> CompletableFuture<List<T>> forkJoinMap(
            int itemCount,
            IntFunction<T> operation,
            String profilerPrefix) {
        Object[] results = new Object[itemCount];
        int grain = forkJoinGrain(itemCount, profilerPrefix);
        long wallStarted = ModelPipelineProfiler.start();
        ModelPipelineProfiler.addCount(profilerPrefix + ".external-tasks", 1);
        ModelPipelineProfiler.addCount(profilerPrefix + ".grain", grain);

        return CompletableFuture.supplyAsync(() -> {
            DEDICATED_POOL.invoke(new RangeAction<>(0, itemCount, grain, operation, results, profilerPrefix));
            ModelPipelineProfiler.end(profilerPrefix + ".workers.wall", wallStarted);
            return ordered(results);
        }, DEDICATED_POOL);
    }

    /** Fallback when users explicitly request Minecraft's executor instead of the dedicated F/J pool. */
    private static <T> CompletableFuture<List<T>> executorMap(
            int itemCount,
            Executor executor,
            IntFunction<T> operation,
            String profilerPrefix) {
        int workers = Math.min(itemCount, Math.max(1, parallelism()));
        int chunk = Math.max(1, NativeAcceleratorConfig.intValue("model.dynamicChunkSize", 2, 1));
        AtomicInteger cursor = new AtomicInteger();
        Object[] results = new Object[itemCount];
        ArrayList<CompletableFuture<Void>> futures = new ArrayList<>(workers);
        long wallStarted = ModelPipelineProfiler.start();

        for (int worker = 0; worker < workers; worker++) {
            futures.add(CompletableFuture.runAsync(() -> {
                long workerStarted = ModelPipelineProfiler.start();
                long workerCpuStarted = ModelPipelineProfiler.startThreadCpu();
                long completed = 0L;
                while (true) {
                    int from = cursor.getAndAdd(chunk);
                    if (from >= itemCount) break;
                    int to = Math.min(itemCount, from + chunk);
                    for (int i = from; i < to; i++) {
                        results[i] = operation.apply(i);
                        completed++;
                    }
                }
                if (workerStarted != 0L) {
                    ModelPipelineProfiler.record(profilerPrefix + ".worker",
                            System.nanoTime() - workerStarted, completed);
                }
                ModelPipelineProfiler.endThreadCpu(profilerPrefix + ".worker", workerCpuStarted, completed);
            }, executor));
        }
        ModelPipelineProfiler.addCount(profilerPrefix + ".external-tasks", workers);
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            ModelPipelineProfiler.end(profilerPrefix + ".workers.wall", wallStarted);
            return ordered(results);
        });
    }

    private static int forkJoinGrain(int itemCount, String profilerPrefix) {
        String family = family(profilerPrefix);
        int familyConfigured = NativeAcceleratorConfig.intValue("model.forkJoinGrain." + family, 0, 0);
        if (familyConfigured > 0) return Math.min(itemCount, familyConfigured);
        int configured = NativeAcceleratorConfig.intValue("model.forkJoinGrain", 0, 0);
        if (configured > 0) return Math.min(itemCount, configured);
        int leavesPerWorker = NativeAcceleratorConfig.intValue("model.forkJoinLeavesPerWorker." + family,
                NativeAcceleratorConfig.intValue("model.forkJoinLeavesPerWorker", 8, 1), 1);
        int targetLeaves = Math.max(1, parallelism() * leavesPerWorker);
        // Keep leaves large enough to amortize F/J bookkeeping while exposing enough pieces for stealing.
        return Math.max(2, (itemCount + targetLeaves - 1) / targetLeaves);
    }

    private static String family(String profilerPrefix) {
        if (profilerPrefix == null || profilerPrefix.isBlank()) return "default";
        if (profilerPrefix.startsWith("raw-model")) return "rawModel";
        if (profilerPrefix.startsWith("blockstate")) return "blockstate";
        if (profilerPrefix.startsWith("item")) return "item";
        if (profilerPrefix.startsWith("atlas")) return "atlas";
        int dot = profilerPrefix.indexOf('.');
        String raw = dot < 0 ? profilerPrefix : profilerPrefix.substring(0, dot);
        return raw.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static <T> List<T> ordered(Object[] results) {
        ArrayList<T> ordered = new ArrayList<>(results.length);
        for (Object result : results) {
            @SuppressWarnings("unchecked") T typed = (T) result;
            ordered.add(typed);
        }
        return ordered;
    }

    private static ForkJoinPool createPool() {
        int parallelism = Math.max(1, parallelism());
        ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            thread.setName("NativeAccelerator-Model-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ForkJoinPool(parallelism, factory, (thread, throwable) -> {
            System.err.println("[Native Accelerator] Uncaught model worker exception on "
                    + thread.getName() + ": " + throwable);
        }, false);
    }

    @SuppressWarnings("serial")
    private static final class ConsumerRangeAction extends RecursiveAction {
        private final int from;
        private final int to;
        private final int grain;
        private final IntConsumer operation;
        private final String profilerPrefix;

        private ConsumerRangeAction(int from, int to, int grain, IntConsumer operation, String profilerPrefix) {
            this.from = from;
            this.to = to;
            this.grain = grain;
            this.operation = operation;
            this.profilerPrefix = profilerPrefix;
        }

        @Override
        protected void compute() {
            int length = to - from;
            if (length <= grain) {
                long started = ModelPipelineProfiler.start();
                long cpuStarted = ModelPipelineProfiler.startThreadCpu();
                for (int i = from; i < to; i++) operation.accept(i);
                if (started != 0L) {
                    ModelPipelineProfiler.record(profilerPrefix + ".leaf", System.nanoTime() - started, length);
                }
                ModelPipelineProfiler.endThreadCpu(profilerPrefix + ".leaf", cpuStarted, length);
                return;
            }
            int middle = from + (length >>> 1);
            invokeAll(
                    new ConsumerRangeAction(from, middle, grain, operation, profilerPrefix),
                    new ConsumerRangeAction(middle, to, grain, operation, profilerPrefix));
        }
    }

    @SuppressWarnings("serial")
    private static final class RangeAction<T> extends RecursiveAction {
        private final int from;
        private final int to;
        private final int grain;
        private final IntFunction<T> operation;
        private final Object[] results;
        private final String profilerPrefix;

        private RangeAction(int from, int to, int grain, IntFunction<T> operation,
                Object[] results, String profilerPrefix) {
            this.from = from;
            this.to = to;
            this.grain = grain;
            this.operation = operation;
            this.results = results;
            this.profilerPrefix = profilerPrefix;
        }

        @Override
        protected void compute() {
            int length = to - from;
            if (length <= grain) {
                long started = ModelPipelineProfiler.start();
                long cpuStarted = ModelPipelineProfiler.startThreadCpu();
                for (int i = from; i < to; i++) results[i] = operation.apply(i);
                if (started != 0L) {
                    ModelPipelineProfiler.record(profilerPrefix + ".leaf", System.nanoTime() - started, length);
                }
                ModelPipelineProfiler.endThreadCpu(profilerPrefix + ".leaf", cpuStarted, length);
                return;
            }
            int middle = from + (length >>> 1);
            invokeAll(
                    new RangeAction<>(from, middle, grain, operation, results, profilerPrefix),
                    new RangeAction<>(middle, to, grain, operation, results, profilerPrefix));
        }
    }
}
