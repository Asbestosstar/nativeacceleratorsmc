package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Prebuilt 32-bit indices that expand a non-indexed triangle fan into a triangle list:
 * (0,1,2), (0,2,3), ...
 *
 * <p>SDL GPU intentionally has no triangle-fan primitive. A static index buffer lets us preserve
 * Minecraft's existing vertex buffers without CPU-copying/rebuilding geometry inside a render pass.</p>
 */
final class MetalFanIndexBuffer implements AutoCloseable {
    private static final int DEFAULT_MAX_VERTICES = 65536;

    private final MetalGpuBuffer buffer;
    private final int maxVertices;

    MetalFanIndexBuffer(MetalDevice device) {
        this.maxVertices = Math.max(3, Integer.getInteger(
                "nativeaccelerator.renderer.metal.fanMaxVertices", DEFAULT_MAX_VERTICES));
        int triangles = maxVertices - 2;
        int indexCount = Math.multiplyExact(triangles, 3);
        ByteBuffer bytes = ByteBuffer.allocateDirect(Math.multiplyExact(indexCount, Integer.BYTES))
                .order(ByteOrder.nativeOrder());
        for (int i = 1; i <= maxVertices - 2; i++) {
            bytes.putInt(0);
            bytes.putInt(i);
            bytes.putInt(i + 1);
        }
        bytes.flip();
        this.buffer = new MetalGpuBuffer(device, GpuBuffer.USAGE_INDEX, bytes);
    }

    long handle() { return buffer.handle(); }

    int indexCountForVertices(int vertexCount) {
        if (vertexCount < 3) return 0;
        if (vertexCount > maxVertices) {
            throw new UnsupportedOperationException(
                    "Triangle fan has " + vertexCount + " vertices; Metal fan compatibility buffer supports "
                            + maxVertices + ". Override with -Dnativeaccelerator.renderer.metal.fanMaxVertices=N");
        }
        return Math.multiplyExact(vertexCount - 2, 3);
    }

    @Override public void close() { buffer.close(); }
}

