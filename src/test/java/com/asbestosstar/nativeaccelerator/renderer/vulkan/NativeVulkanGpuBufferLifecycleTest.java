package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Device-free tests for {@link NativeVulkanGpuBuffer}: the close-versus-mapping lease policy and the
 * ownership/liveness checks that run before any Vulkan object is created. No VkDevice is created and no
 * GPU behavior is claimed. The constructor checks use uninitialized allocator and bootstrap instances,
 * so the tests fail if a check is missing and a Vulkan call is reached.
 */
public final class NativeVulkanGpuBufferLifecycleTest {
    public static void main(String[] args) throws Exception {
        leaseFreeCloseDestroysOnce();
        closeWithLeaseDefersDestroyUntilFlushedRelease();
        releaseIsIdempotent();
        lastReleaseDestroysAfterEveryLease();
        readOnlyReleaseDoesNotFlush();
        flushFailureStillReleasesAndDestroys();
        destroyFailureIsReportedOnceAndNotRetried();
        openReleaseKeepsBufferAlive();
        closeSerializesOnDeviceStateMonitor();
        concurrentReleasesDestroyOnceAfterAllFlushes();
        constructorRejectsMissingBootstrap();
        constructorRejectsForeignAllocatorBeforeCreation();
        constructorRejectsClosedAllocatorBeforeCreation();
        constructorRejectsClosedDeviceBeforeCreation();
        System.out.println("NativeVulkanGpuBufferLifecycleTest: PASS");
    }

    private static void leaseFreeCloseDestroysOnce() {
        ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        NativeVulkanGpuBuffer.Lifecycle lifecycle = lifecycle(new Object(), events);
        require(!lifecycle.isClosed(), "new lifecycle reports closed");
        require(lifecycle.close(), "first close did not win the transition");
        require(lifecycle.isClosed(), "closed flag not visible after close");
        require(lifecycle.isDestroyed(), "lease-free close did not destroy immediately");
        require(!lifecycle.close(), "second close won the transition again");
        require(List.copyOf(events).equals(List.of("destroy")), "unexpected events " + events);
        requireThrows(() -> lifecycle.acquire(), IllegalStateException.class, "closed buffer accepted a mapping");
    }

    private static void closeWithLeaseDefersDestroyUntilFlushedRelease() {
        ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        NativeVulkanGpuBuffer.Lifecycle lifecycle = lifecycle(new Object(), events);
        var lease = lifecycle.acquire();
        require(lifecycle.activeLeases() == 1, "acquire did not record the lease");
        require(lifecycle.close(), "close with a live lease did not close");
        require(lifecycle.isClosed() && !lifecycle.isDestroyed(),
                "close with a live lease destroyed memory under the mapping");
        requireThrows(() -> lifecycle.acquire(), IllegalStateException.class,
                "closing buffer accepted a new mapping");
        require(events.isEmpty(), "destroy or flush ran before the lease was released: " + events);
        lifecycle.release(lease, flush(events));
        require(List.copyOf(events).equals(List.of("flush", "destroy")), "release order wrong: " + events);
        require(lifecycle.isDestroyed() && lifecycle.activeLeases() == 0, "deferred destroy did not complete");
    }

    private static void releaseIsIdempotent() {
        ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        NativeVulkanGpuBuffer.Lifecycle lifecycle = lifecycle(new Object(), events);
        var lease = lifecycle.acquire();
        lifecycle.release(lease, flush(events));
        lifecycle.release(lease, flush(events));
        require(lifecycle.activeLeases() == 0, "double release corrupted the lease count");
        require(List.copyOf(events).equals(List.of("flush")), "double release flushed twice: " + events);
        require(lifecycle.close(), "close after release did not win");
        require(List.copyOf(events).equals(List.of("flush", "destroy")), "unexpected events after close: " + events);
    }

    private static void lastReleaseDestroysAfterEveryLease() {
        ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        NativeVulkanGpuBuffer.Lifecycle lifecycle = lifecycle(new Object(), events);
        var first = lifecycle.acquire();
        var second = lifecycle.acquire();
        lifecycle.close();
        lifecycle.release(first, flush(events));
        require(!lifecycle.isDestroyed(), "first of two releases destroyed the buffer");
        lifecycle.release(second, flush(events));
        require(List.copyOf(events).equals(List.of("flush", "flush", "destroy")), "two-lease order wrong: " + events);
    }

    private static void readOnlyReleaseDoesNotFlush() {
        ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        NativeVulkanGpuBuffer.Lifecycle lifecycle = lifecycle(new Object(), events);
        var lease = lifecycle.acquire();
        lifecycle.close();
        lifecycle.release(lease, null);
        require(List.copyOf(events).equals(List.of("destroy")), "read-only release flushed: " + events);
    }

    private static void flushFailureStillReleasesAndDestroys() {
        ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        NativeVulkanGpuBuffer.Lifecycle lifecycle = lifecycle(new Object(), events);
        var lease = lifecycle.acquire();
        lifecycle.close();
        Runnable failingFlush = () -> {
            events.add("flush");
            throw new IllegalStateException("flush failed");
        };
        requireThrows(() -> lifecycle.release(lease, failingFlush), IllegalStateException.class,
                "flush failure was swallowed");
        require(lifecycle.activeLeases() == 0, "failed flush leaked the lease count");
        require(lifecycle.isDestroyed(), "failed flush prevented the deferred destroy");
        require(List.copyOf(events).equals(List.of("flush", "destroy")), "unexpected events after failed flush: " + events);
    }

    private static void destroyFailureIsReportedOnceAndNotRetried() {
        AtomicInteger destroys = new AtomicInteger();
        NativeVulkanGpuBuffer.Lifecycle lifecycle = new NativeVulkanGpuBuffer.Lifecycle(new Object(), () -> {
            destroys.incrementAndGet();
            throw new IllegalStateException("free failed");
        });
        requireThrows(lifecycle::close, IllegalStateException.class, "destroy failure was swallowed");
        require(!lifecycle.close(), "second close retried destruction");
        require(destroys.get() == 1, "destroyer ran " + destroys.get() + " times");
    }

    private static void openReleaseKeepsBufferAlive() {
        ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        NativeVulkanGpuBuffer.Lifecycle lifecycle = lifecycle(new Object(), events);
        var lease = lifecycle.acquire();
        lifecycle.release(lease, flush(events));
        require(!lifecycle.isDestroyed() && !lifecycle.isClosed(), "open release tore the buffer down");
        var again = lifecycle.acquire();
        lifecycle.release(again, null);
        require(List.copyOf(events).equals(List.of("flush")), "unexpected events while open: " + events);
        require(lifecycle.close(), "close after open releases did not win");
        require(List.copyOf(events).equals(List.of("flush", "destroy")), "unexpected events after close: " + events);
    }

    private static void closeSerializesOnDeviceStateMonitor() throws Exception {
        Object monitor = new Object();
        ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        NativeVulkanGpuBuffer.Lifecycle lifecycle = lifecycle(monitor, events);
        Thread closer = new Thread(lifecycle::close, "lifecycle-close-under-test");
        synchronized (monitor) {
            closer.start();
            awaitThreadState(closer, Thread.State.BLOCKED);
            require(!lifecycle.isClosed(), "close ran while the device-state monitor was held");
            require(events.isEmpty(), "destroy ran while the device-state monitor was held");
        }
        closer.join(TimeUnit.SECONDS.toMillis(5));
        require(!closer.isAlive(), "close did not complete after the monitor was released");
        require(lifecycle.isDestroyed(), "close did not destroy after the monitor was released");
    }

    private static void concurrentReleasesDestroyOnceAfterAllFlushes() throws Exception {
        final int leaseCount = 16;
        AtomicInteger flushes = new AtomicInteger();
        AtomicInteger destroys = new AtomicInteger();
        AtomicReference<String> violation = new AtomicReference<>();
        NativeVulkanGpuBuffer.Lifecycle lifecycle = new NativeVulkanGpuBuffer.Lifecycle(new Object(), () -> {
            destroys.incrementAndGet();
            if (flushes.get() != leaseCount) {
                violation.compareAndSet(null, "destroyed after only " + flushes.get() + " flushes");
            }
        });
        List<NativeVulkanGpuBuffer.Lifecycle.Lease> held = new ArrayList<>();
        for (int i = 0; i < leaseCount; i++) held.add(lifecycle.acquire());
        lifecycle.close();
        require(!lifecycle.isDestroyed(), "close destroyed while leases were live");

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (var lease : held) {
                Callable<Object> release = () -> {
                    start.await();
                    lifecycle.release(lease, () -> flushes.incrementAndGet());
                    return null;
                };
                futures.add(pool.submit(release));
            }
            start.countDown();
            for (Future<Object> future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        require(violation.get() == null, String.valueOf(violation.get()));
        require(flushes.get() == leaseCount, "flush count " + flushes.get());
        require(destroys.get() == 1, "destroy count " + destroys.get());
        require(lifecycle.activeLeases() == 0, "leases leaked: " + lifecycle.activeLeases());
    }

    private static void constructorRejectsMissingBootstrap() throws Exception {
        NativeVulkanGpuMemoryAllocator allocator = allocateUninitialized(NativeVulkanGpuMemoryAllocator.class);
        var failure = expectThrow(() -> newBuffer(null, new NativeVulkanDeviceState(), allocator),
                NullPointerException.class, "null bootstrap accepted");
        require(String.valueOf(failure.getMessage()).contains("bootstrap"), "null bootstrap message: " + failure.getMessage());
    }

    private static void constructorRejectsForeignAllocatorBeforeCreation() throws Exception {
        NativeVulkanDeviceBootstrap owner = allocateUninitialized(NativeVulkanDeviceBootstrap.class);
        NativeVulkanDeviceBootstrap other = allocateUninitialized(NativeVulkanDeviceBootstrap.class);
        NativeVulkanGpuMemoryAllocator allocator = allocateUninitialized(NativeVulkanGpuMemoryAllocator.class);
        setField(allocator, "bootstrap", other);
        var failure = expectThrow(() -> newBuffer(owner, new NativeVulkanDeviceState(), allocator),
                IllegalArgumentException.class, "allocator from another bootstrap accepted");
        require(failure.getMessage().contains("different device bootstrap"), "foreign allocator message: " + failure.getMessage());
    }

    private static void constructorRejectsClosedAllocatorBeforeCreation() throws Exception {
        NativeVulkanDeviceBootstrap owner = allocateUninitialized(NativeVulkanDeviceBootstrap.class);
        NativeVulkanGpuMemoryAllocator allocator = allocateUninitialized(NativeVulkanGpuMemoryAllocator.class);
        setField(allocator, "bootstrap", owner);
        setField(allocator, "closed", true);
        var failure = expectThrow(() -> newBuffer(owner, new NativeVulkanDeviceState(), allocator),
                IllegalStateException.class, "closed allocator accepted");
        require(failure.getMessage().contains("allocator is closed"), "closed allocator message: " + failure.getMessage());
    }

    private static void constructorRejectsClosedDeviceBeforeCreation() throws Exception {
        NativeVulkanDeviceBootstrap owner = allocateUninitialized(NativeVulkanDeviceBootstrap.class);
        NativeVulkanGpuMemoryAllocator allocator = allocateUninitialized(NativeVulkanGpuMemoryAllocator.class);
        setField(allocator, "bootstrap", owner);
        NativeVulkanDeviceState state = new NativeVulkanDeviceState();
        state.abort();
        var failure = expectThrow(() -> newBuffer(owner, state, allocator),
                IllegalStateException.class, "closed device accepted");
        require(failure.getMessage().contains("device is closed"), "closed device message: " + failure.getMessage());
    }

    private static NativeVulkanGpuBuffer.Lifecycle lifecycle(Object monitor, ConcurrentLinkedQueue<String> events) {
        return new NativeVulkanGpuBuffer.Lifecycle(monitor, () -> events.add("destroy"));
    }

    private static Runnable flush(ConcurrentLinkedQueue<String> events) {
        return () -> events.add("flush");
    }

    private static NativeVulkanGpuBuffer newBuffer(NativeVulkanDeviceBootstrap bootstrap,
                                                   NativeVulkanDeviceState state,
                                                   NativeVulkanGpuMemoryAllocator allocator) {
        return new NativeVulkanGpuBuffer(bootstrap, state, allocator, GpuBuffer.USAGE_VERTEX, 64L);
    }

    /** Allocates an instance without running its constructor, so no Vulkan loader or device is touched. */
    private static <T> T allocateUninitialized(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Object instance = unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
        return type.cast(instance);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void awaitThreadState(Thread thread, Thread.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("thread stayed " + thread.getState() + ", expected " + expected);
            }
            Thread.sleep(1L);
        }
    }

    private static void requireThrows(Runnable action, Class<? extends Throwable> type, String message) {
        expectThrow(action, type, message);
    }

    private static Throwable expectThrow(Runnable action, Class<? extends Throwable> type, String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) return thrown;
            throw new AssertionError(message + "; threw " + thrown, thrown);
        }
        throw new AssertionError(message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
