package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Iterator;

/** Bounded reuse pool for CPU staging blocks whose contents have been copied into SDL transfer memory. */
final class MetalUploadBufferPool {
    private static final long MAX_RETAINED_BYTES = 16L * 1024 * 1024;
    private static final ArrayDeque<ByteBuffer> AVAILABLE = new ArrayDeque<>();
    private static long retainedBytes;

    private MetalUploadBufferPool() {}

    static synchronized ByteBuffer acquire(int minimumCapacity, int preferredCapacity) {
        if (minimumCapacity < 0 || preferredCapacity < 0) {
            throw new IllegalArgumentException("Invalid upload block capacity");
        }
        ByteBuffer best = null;
        for (Iterator<ByteBuffer> it = AVAILABLE.iterator(); it.hasNext();) {
            ByteBuffer candidate = it.next();
            if (candidate.capacity() < minimumCapacity) continue;
            if (best == null || candidate.capacity() < best.capacity()) best = candidate;
        }
        if (best != null) {
            AVAILABLE.remove(best);
            retainedBytes -= best.capacity();
            return best.clear().order(ByteOrder.nativeOrder());
        }
        return ByteBuffer.allocateDirect(Math.max(minimumCapacity, preferredCapacity))
                .order(ByteOrder.nativeOrder());
    }

    static synchronized void release(ByteBuffer buffer) {
        if (buffer == null) return;
        int capacity = buffer.capacity();
        if (capacity == 0 || capacity > MAX_RETAINED_BYTES
                || retainedBytes + capacity > MAX_RETAINED_BYTES) return;
        buffer.clear();
        AVAILABLE.addLast(buffer);
        retainedBytes += capacity;
    }

    static synchronized long retainedBytesForTests() {
        return retainedBytes;
    }
}
