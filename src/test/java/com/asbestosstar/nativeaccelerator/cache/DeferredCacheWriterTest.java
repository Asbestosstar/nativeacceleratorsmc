package com.asbestosstar.nativeaccelerator.cache;

import java.util.concurrent.atomic.AtomicInteger;

/** Standalone regression test: no cache write may start during reload or before the first playable screen. */
public final class DeferredCacheWriterTest {
    public static void main(String[] args) throws Exception {
        AtomicInteger completed = new AtomicInteger();
        DeferredCacheWriter.beginReload();
        if (!DeferredCacheWriter.submit("test", 16, completed::incrementAndGet)) throw new AssertionError("stage rejected");
        Thread.sleep(80L);
        if (completed.get() != 0) throw new AssertionError("write ran during reload");
        DeferredCacheWriter.endReload();
        Thread.sleep(80L);
        if (completed.get() != 0) throw new AssertionError("write ran before first playable screen");
        DeferredCacheWriter.startupComplete();
        await(completed, 1);

        DeferredCacheWriter.beginReload();
        if (!DeferredCacheWriter.submit("test", 16, completed::incrementAndGet)) throw new AssertionError("second stage rejected");
        Thread.sleep(80L);
        if (completed.get() != 1) throw new AssertionError("later reload did not hold write");
        DeferredCacheWriter.endReload();
        await(completed, 2);
        System.out.println("PASS deferred cache writer holds startup + reload writes");
    }

    private static void await(AtomicInteger completed, int expected) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (completed.get() != expected && System.nanoTime() < deadline) Thread.sleep(10L);
        if (completed.get() != expected) throw new AssertionError("timed out waiting for " + expected + ", got " + completed.get());
    }
}

