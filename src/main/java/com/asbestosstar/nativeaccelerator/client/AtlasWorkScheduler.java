package com.asbestosstar.nativeaccelerator.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * Bounded lower-priority scheduler for atlas preparation.
 *
 * <p>Atlas work used to share {@link ModelWorkScheduler}'s CPU-wide pool. Once resource enumeration became
 * much faster, sprite loading and mip generation became runnable at the same time as blockstate/model/item
 * decoding and could consume the cores on ModelManager's longer critical path. This pool intentionally has
 * much less parallelism and lower-priority daemon workers. Atlas work still progresses concurrently, but it
 * cannot fan out to every model worker.</p>
 */
public final class AtlasWorkScheduler {
    private static final int AVAILABLE_PROCESSORS = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ForkJoinPool POOL = createPool();

    private AtlasWorkScheduler() {}

    public static int parallelism() {
        int configured = NativeAcceleratorConfig.intValue("atlas.workers", 0, 0);
        if (configured > 0) return configured;
        // Leave most CPU capacity to the model/item/blockstate critical path.
        return Math.max(1, Math.min(4, AVAILABLE_PROCESSORS / 4));
    }

    /**
     * Batches sprite-source calls onto the bounded atlas pool while preserving result order.
     */
    public static <T> CompletableFuture<List<T>> mapIndexed(
            int itemCount,
            Executor minecraftExecutor,
            IntFunction<T> operation,
            String profilerPrefix) {
        if (itemCount == 0) return CompletableFuture.completedFuture(List.of());
        if (!NativeAcceleratorConfig.booleanValue("atlas.dedicatedPool", true)) {
            return fallbackMap(itemCount, minecraftExecutor, operation, profilerPrefix);
        }

        Object[] results = new Object[itemCount];
        int workers = Math.min(itemCount, parallelism());
        int chunk = Math.max(1, NativeAcceleratorConfig.intValue("atlas.dynamicChunkSize", 2, 1));
        AtomicInteger cursor = new AtomicInteger();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[workers];
        long wallStarted = ModelPipelineProfiler.start();

        for (int worker = 0; worker < workers; worker++) {
            futures[worker] = CompletableFuture.runAsync(() -> {
                long elapsedStarted = ModelPipelineProfiler.start();
                long cpuStarted = ModelPipelineProfiler.startThreadCpu();
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
                if (elapsedStarted != 0L) {
                    ModelPipelineProfiler.record(profilerPrefix + ".worker",
                            System.nanoTime() - elapsedStarted, completed);
                }
                ModelPipelineProfiler.endThreadCpu(profilerPrefix + ".worker", cpuStarted, completed);
            }, POOL);
        }

        ModelPipelineProfiler.addCount(profilerPrefix + ".external-tasks", workers);
        ModelPipelineProfiler.addCount(profilerPrefix + ".pool-parallelism", parallelism());
        return CompletableFuture.allOf(futures).thenApply(ignored -> {
            ModelPipelineProfiler.end(profilerPrefix + ".workers.wall", wallStarted);
            return ordered(results);
        });
    }

    /**
     * Generates mip levels using pixel-weighted bins rather than one equal-cost task per sprite.
     * Small atlases run as one low-priority task because parallel bookkeeping costs more than the work.
     */
    public static CompletableFuture<Void> runMipmaps(
            List<TextureAtlasSprite> sprites,
            int mipLevel,
            Executor minecraftExecutor,
            String profilerPrefix) {
        if (sprites.isEmpty() || mipLevel <= 0) return CompletableFuture.completedFuture(null);

        long[] weights = new long[sprites.size()];
        long totalWeight = 0L;
        for (int i = 0; i < sprites.size(); i++) {
            TextureAtlasSprite sprite = sprites.get(i);
            long weight = mipPixelWork(sprite.contents().width(), sprite.contents().height(), mipLevel);
            weights[i] = Math.max(1L, weight);
            totalWeight = saturatingAdd(totalWeight, weights[i]);
        }

        ModelPipelineProfiler.addCount(profilerPrefix + ".estimated-pixels", totalWeight);
        ModelPipelineProfiler.addCount(profilerPrefix + ".sprites", sprites.size());

        int serialPixels = NativeAcceleratorConfig.intValue("atlas.mipmapSerialPixels", 384 * 1024, 0);
        int serialSprites = NativeAcceleratorConfig.intValue("atlas.mipmapSerialSprites", 24, 0);
        boolean serial = totalWeight <= serialPixels || sprites.size() <= serialSprites || parallelism() <= 1;

        Executor targetExecutor = NativeAcceleratorConfig.booleanValue("atlas.dedicatedPool", true)
                ? POOL : minecraftExecutor;
        long wallStarted = ModelPipelineProfiler.start();

        if (serial) {
            ModelPipelineProfiler.addCount(profilerPrefix + ".serial-atlas", 1);
            return CompletableFuture.runAsync(() -> {
                long elapsedStarted = ModelPipelineProfiler.start();
                long cpuStarted = ModelPipelineProfiler.startThreadCpu();
                for (TextureAtlasSprite sprite : sprites) sprite.contents().increaseMipLevel(mipLevel);
                if (elapsedStarted != 0L) {
                    ModelPipelineProfiler.record(profilerPrefix + ".leaf",
                            System.nanoTime() - elapsedStarted, sprites.size());
                }
                ModelPipelineProfiler.endThreadCpu(profilerPrefix + ".leaf", cpuStarted, sprites.size());
            }, targetExecutor).whenComplete((ignored, failure) ->
                    ModelPipelineProfiler.end(profilerPrefix + ".workers.wall", wallStarted));
        }

        int workers = Math.min(sprites.size(), parallelism());
        int chunksPerWorker = NativeAcceleratorConfig.intValue("atlas.mipmapChunksPerWorker", 2, 1);
        int binCount = Math.min(sprites.size(), Math.max(workers, workers * chunksPerWorker));
        int[][] bins = weightedBins(weights, binCount);
        CompletableFuture<?>[] futures = new CompletableFuture<?>[bins.length];
        ModelPipelineProfiler.addCount(profilerPrefix + ".bins", bins.length);
        ModelPipelineProfiler.addCount(profilerPrefix + ".pool-parallelism", parallelism());

        for (int bin = 0; bin < bins.length; bin++) {
            int[] indices = bins[bin];
            futures[bin] = CompletableFuture.runAsync(() -> {
                long elapsedStarted = ModelPipelineProfiler.start();
                long cpuStarted = ModelPipelineProfiler.startThreadCpu();
                for (int index : indices) sprites.get(index).contents().increaseMipLevel(mipLevel);
                if (elapsedStarted != 0L) {
                    ModelPipelineProfiler.record(profilerPrefix + ".leaf",
                            System.nanoTime() - elapsedStarted, indices.length);
                }
                ModelPipelineProfiler.endThreadCpu(profilerPrefix + ".leaf", cpuStarted, indices.length);
            }, targetExecutor);
        }

        return CompletableFuture.allOf(futures).whenComplete((ignored, failure) ->
                ModelPipelineProfiler.end(profilerPrefix + ".workers.wall", wallStarted));
    }

    static long mipPixelWork(int width, int height, int mipLevel) {
        long total = 0L;
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        for (int level = 1; level <= mipLevel; level++) {
            w = Math.max(1, w >> 1);
            h = Math.max(1, h >> 1);
            total = saturatingAdd(total, (long) w * h);
        }
        return total;
    }

    /** Longest-processing-time partitioning into a small fixed set of bins. */
    static int[][] weightedBins(long[] weights, int requestedBins) {
        if (weights.length == 0) return new int[0][];
        int bins = Math.max(1, Math.min(requestedBins, weights.length));
        long[] packed = new long[weights.length];
        for (int i = 0; i < weights.length; i++) {
            long normalized = Math.max(0L, Math.min(0x7fffffffL, weights[i]));
            packed[i] = (normalized << 32) | (i & 0xffffffffL);
        }
        Arrays.sort(packed);

        long[] loads = new long[bins];
        int[] assignment = new int[weights.length];
        int[] counts = new int[bins];
        for (int p = packed.length - 1; p >= 0; p--) {
            int index = (int) packed[p];
            int lightest = 0;
            for (int b = 1; b < bins; b++) {
                if (loads[b] < loads[lightest]) lightest = b;
            }
            assignment[index] = lightest;
            counts[lightest]++;
            loads[lightest] = saturatingAdd(loads[lightest], weights[index]);
        }

        int[][] result = new int[bins][];
        for (int b = 0; b < bins; b++) result[b] = new int[counts[b]];
        Arrays.fill(counts, 0);
        // Preserve original order inside a bin. Only cross-bin execution order changes.
        for (int index = 0; index < assignment.length; index++) {
            int bin = assignment[index];
            result[bin][counts[bin]++] = index;
        }
        return result;
    }

    private static <T> CompletableFuture<List<T>> fallbackMap(
            int itemCount, Executor executor, IntFunction<T> operation, String profilerPrefix) {
        Object[] results = new Object[itemCount];
        int workers = Math.min(itemCount, parallelism());
        AtomicInteger cursor = new AtomicInteger();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[workers];
        long wallStarted = ModelPipelineProfiler.start();
        for (int worker = 0; worker < workers; worker++) {
            futures[worker] = CompletableFuture.runAsync(() -> {
                int index;
                while ((index = cursor.getAndIncrement()) < itemCount) results[index] = operation.apply(index);
            }, executor);
        }
        return CompletableFuture.allOf(futures).thenApply(ignored -> {
            ModelPipelineProfiler.end(profilerPrefix + ".workers.wall", wallStarted);
            return ordered(results);
        });
    }

    private static <T> List<T> ordered(Object[] results) {
        ArrayList<T> ordered = new ArrayList<>(results.length);
        for (Object result : results) {
            @SuppressWarnings("unchecked") T typed = (T) result;
            ordered.add(typed);
        }
        return ordered;
    }

    private static long saturatingAdd(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) return Long.MAX_VALUE;
        return result;
    }

    private static ForkJoinPool createPool() {
        int parallelism = Math.max(1, parallelism());
        ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            thread.setName("NativeAccelerator-Atlas-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            int requested = NativeAcceleratorConfig.intValue("atlas.workerPriority",
                    Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1), Thread.MIN_PRIORITY);
            try {
                thread.setPriority(Math.min(Thread.MAX_PRIORITY, requested));
            } catch (SecurityException ignored) {
                // Keep the bounded pool even when the runtime forbids priority changes.
            }
            return thread;
        };
        return new ForkJoinPool(parallelism, factory, (thread, throwable) ->
                System.err.println("[Native Accelerator] Uncaught atlas worker exception on "
                        + thread.getName() + ": " + throwable), false);
    }
}

