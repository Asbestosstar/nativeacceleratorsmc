package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.nio.ByteBuffer;

/** Vulkan/Metal compatible indirect argument decoding used by the Mac1/patched-driver fallback. */
final class MetalIndirectCommandDecoder {
    static final int DRAW_BYTES = 16;
    static final int INDEXED_DRAW_BYTES = 20;

    record Draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {}
    record IndexedDraw(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {}

    private MetalIndirectCommandDecoder() {}

    static Draw draw(ByteBuffer bytes, int commandIndex) {
        int base = Math.multiplyExact(commandIndex, DRAW_BYTES);
        return new Draw(bytes.getInt(base), bytes.getInt(base + 4), bytes.getInt(base + 8), bytes.getInt(base + 12));
    }

    static IndexedDraw indexed(ByteBuffer bytes, int commandIndex) {
        int base = Math.multiplyExact(commandIndex, INDEXED_DRAW_BYTES);
        return new IndexedDraw(bytes.getInt(base), bytes.getInt(base + 4), bytes.getInt(base + 8),
                bytes.getInt(base + 12), bytes.getInt(base + 16));
    }
}

