package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.mojang.renderpearl.api.buffers.GpuBuffer;

final class RenderPearlUrdBuffer implements UrdBuffer {
    private final GpuBuffer nativeBuffer;
    private boolean closed;

    RenderPearlUrdBuffer(GpuBuffer nativeBuffer) { this.nativeBuffer = nativeBuffer; }
    GpuBuffer nativeBuffer() { requireOpen(); return nativeBuffer; }
    @Override public long size() { requireOpen(); return nativeBuffer.size(); }
    @Override public void upload(long offset, java.nio.ByteBuffer data) {
        requireOpen();
        if (offset < 0 || data == null || data.remaining() > nativeBuffer.size() - offset) {
            throw new UrdValidationException("buffer upload out of bounds");
        }
        try (var mapped = nativeBuffer.map(offset, data.remaining(), false, true)) {
            mapped.data().put(data.duplicate());
        }
    }
    @Override public boolean isClosed() { return closed || nativeBuffer.isClosed(); }
    @Override public void close() { if (!closed) { closed = true; nativeBuffer.close(); } }
    @Override public void requireOpen() { if (isClosed()) throw new UrdValidationException("buffer is closed"); }
}
