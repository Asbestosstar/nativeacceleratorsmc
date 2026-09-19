package com.asbestosstar.nativeaccelerator.cache;

import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-priority persistent-cache writer that never puts cache I/O on the resource-reload critical path.
 *
 * <p>Misses may stage already-produced bytes while a reload is active, but no file writes are started until
 * the reload has finished. During initial startup the queue remains held until Minecraft reports the first
 * playable screen. Later reloads are held only for that reload. This keeps a cold cache close to the live
 * resource path while preserving warm-cache benefits on subsequent launches.</p>
 */
public final class DeferredCacheWriter {
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("cache.deferredWrites", true);
    private static final int MAX_PENDING_TASKS = NativeAcceleratorConfig.intValue(
            "cache.deferredWrites.maxPendingTasks", 16_384, 1);
    private static final long MAX_PENDING_BYTES = (long) NativeAcceleratorConfig.intValue(
            "cache.deferredWrites.maxPendingMiB", 96, 1) * 1024L * 1024L;

    private static final ConcurrentLinkedQueue<WriteTask> PENDING = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger PENDING_TASKS = new AtomicInteger();
    private static final AtomicLong PENDING_BYTES = new AtomicLong();
    private static final AtomicInteger RELOAD_HOLDS = new AtomicInteger();
    private static final AtomicBoolean STARTUP_COMPLETE = new AtomicBoolean();
    private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean();
    private static volatile ExecutorService writer;

    private DeferredCacheWriter() {}

    /** Prevents newly staged writes from starting until the matching reload completes. */
    public static void beginReload() {
        if (!ENABLED) return;
        RELOAD_HOLDS.incrementAndGet();
    }

    /** Releases one reload hold. Initial-startup writes remain held until {@link #startupComplete()}. */
    public static void endReload() {
        if (!ENABLED) return;
        RELOAD_HOLDS.updateAndGet(value -> Math.max(0, value - 1));
        scheduleDrainIfAllowed();
    }

    /** Called when the first playable client screen is shown. */
    public static void startupComplete() {
        if (!ENABLED) return;
        STARTUP_COMPLETE.set(true);
        scheduleDrainIfAllowed();
    }

    /**
     * Stages a cache write. retainedBytes is the amount of payload memory retained by the closure until run.
     * Returns false when the bounded queue is full so callers can simply skip learning that entry.
     */
    public static boolean submit(String family, int retainedBytes, Runnable action) {
        if (!ENABLED || action == null || retainedBytes < 0) return false;
        int tasks = PENDING_TASKS.incrementAndGet();
        if (tasks > MAX_PENDING_TASKS) {
            PENDING_TASKS.decrementAndGet();
            ModelPipelineProfiler.addCount("cache.deferred.drop-task-limit", 1);
            return false;
        }
        long bytes = PENDING_BYTES.addAndGet(retainedBytes);
        if (bytes > MAX_PENDING_BYTES) {
            PENDING_BYTES.addAndGet(-retainedBytes);
            PENDING_TASKS.decrementAndGet();
            ModelPipelineProfiler.addCount("cache.deferred.drop-byte-limit", 1);
            return false;
        }
        PENDING.add(new WriteTask(family == null ? "cache" : family, retainedBytes, action));
        ModelPipelineProfiler.addCount("cache.deferred.staged", 1);
        ModelPipelineProfiler.addCount("cache.deferred.staged-bytes", retainedBytes);
        scheduleDrainIfAllowed();
        return true;
    }

    /** Drops queued-but-not-started work, used when the mods/resourcepacks generation changes. */
    public static void discardPending() {
        WriteTask task;
        long bytes = 0L;
        int tasks = 0;
        while ((task = PENDING.poll()) != null) {
            bytes += task.retainedBytes;
            tasks++;
        }
        if (tasks != 0) {
            PENDING_TASKS.addAndGet(-tasks);
            PENDING_BYTES.addAndGet(-bytes);
            ModelPipelineProfiler.addCount("cache.deferred.discarded", tasks);
        }
    }

    public static int pendingTasks() {
        return Math.max(0, PENDING_TASKS.get());
    }

    public static long pendingBytes() {
        return Math.max(0L, PENDING_BYTES.get());
    }

    private static boolean allowedToDrain() {
        return STARTUP_COMPLETE.get() && RELOAD_HOLDS.get() == 0;
    }

    private static void scheduleDrainIfAllowed() {
        if (!ENABLED || !allowedToDrain() || PENDING.isEmpty()) return;
        if (!DRAIN_SCHEDULED.compareAndSet(false, true)) return;
        writer().execute(DeferredCacheWriter::drain);
    }

    private static void drain() {
        try {
            while (allowedToDrain()) {
                WriteTask task = PENDING.poll();
                if (task == null) break;
                long started = ModelPipelineProfiler.start();
                try {
                    task.action.run();
                    ModelPipelineProfiler.addCount("cache.deferred.written", 1);
                } catch (Throwable failure) {
                    ModelPipelineProfiler.addCount("cache.deferred.write-error", 1);
                } finally {
                    PENDING_TASKS.decrementAndGet();
                    PENDING_BYTES.addAndGet(-task.retainedBytes);
                    ModelPipelineProfiler.end("cache.deferred.write." + task.family, started);
                }
            }
        } finally {
            DRAIN_SCHEDULED.set(false);
            if (allowedToDrain() && !PENDING.isEmpty()) scheduleDrainIfAllowed();
        }
    }

    private static ExecutorService writer() {
        ExecutorService current = writer;
        if (current != null) return current;
        synchronized (DeferredCacheWriter.class) {
            if (writer != null) return writer;
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "NativeAccelerator-CacheWriter");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            };
            writer = Executors.newSingleThreadExecutor(factory);
            return writer;
        }
    }

    private record WriteTask(String family, int retainedBytes, Runnable action) {}
}

