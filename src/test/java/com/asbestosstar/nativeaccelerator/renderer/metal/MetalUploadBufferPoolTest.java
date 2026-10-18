package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.nio.ByteBuffer;

public final class MetalUploadBufferPoolTest {
    public static void main(String[] args) {
        long retainedBefore = MetalUploadBufferPool.retainedBytesForTests();
        ByteBuffer first = MetalUploadBufferPool.acquire(1024, 4096);
        if (first.capacity() < 4096) throw new AssertionError("preferred staging capacity not honored");
        first.putInt(0x12345678);
        MetalUploadBufferPool.release(first);

        ByteBuffer reused = MetalUploadBufferPool.acquire(1024, 4096);
        if (reused != first) throw new AssertionError("released staging block was not reused");
        if (reused.position() != 0) throw new AssertionError("reused staging block was not cleared");
        if (MetalUploadBufferPool.retainedBytesForTests() != retainedBefore) {
            throw new AssertionError("acquiring a pooled block should release its retained capacity");
        }
        MetalUploadBufferPool.release(reused);

        MetalTransientMemory transientMemory = new MetalTransientMemory(null);
        ByteBuffer retainedUntilCompletion = MetalUploadBufferPool.acquire(1024, 4096);
        transientMemory.retainUploadBlocks(java.util.List.of(retainedUntilCompletion));
        if (transientMemory.isEmpty()) throw new AssertionError("retained staging block must make transient memory non-empty");
        if (MetalUploadBufferPool.retainedBytesForTests() != retainedBefore) {
            throw new AssertionError("in-flight staging block returned to pool before retirement");
        }
        transientMemory.release();
        if (transientMemory.isEmpty() == false) throw new AssertionError("released transient memory should be empty");
        if (MetalUploadBufferPool.retainedBytesForTests() != retainedBefore + retainedUntilCompletion.capacity()) {
            throw new AssertionError("retired staging block was not returned to the pool");
        }

        ByteBuffer oversizedRequest = MetalUploadBufferPool.acquire(8192, 4096);
        if (oversizedRequest.capacity() < 8192) throw new AssertionError("minimum capacity not honored");
        MetalUploadBufferPool.release(oversizedRequest);
        if (MetalUploadBufferPool.retainedBytesForTests() > 16L * 1024 * 1024) {
            throw new AssertionError("pool retained more than its configured bound");
        }

        ByteBuffer tooLargeToRetain = ByteBuffer.allocateDirect(16 * 1024 * 1024 + 1);
        long boundedBefore = MetalUploadBufferPool.retainedBytesForTests();
        MetalUploadBufferPool.release(tooLargeToRetain);
        if (MetalUploadBufferPool.retainedBytesForTests() != boundedBefore) {
            throw new AssertionError("pool retained a block larger than the retention limit");
        }
        System.out.println("MetalUploadBufferPoolTest: PASS");
    }
}
