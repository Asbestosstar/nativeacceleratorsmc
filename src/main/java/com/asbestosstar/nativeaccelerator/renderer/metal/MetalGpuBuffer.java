package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

final class MetalGpuBuffer extends BaseGpuBuffer {
    private final MetalDevice device;
    private final long handle;
    private final ByteBuffer shadow;
    private final AtomicBoolean closed = new AtomicBoolean();

    MetalGpuBuffer(MetalDevice device, int usage, long size) {
        super(usage, size);
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("SDL GPU buffer size outside Java shadow range: " + size);
        }
        this.device = device;
        Object info = MetalInterop.calloc("SDL_GPUBufferCreateInfo");
        try {
            MetalInterop.set(info, "usage", MetalConversions.bufferUsage(usage));
            MetalInterop.set(info, "size", (int)size);
            this.handle = MetalInterop.sdlLong("SDL_CreateGPUBuffer", device.handle(), info);
        } finally {
            MetalInterop.free(info);
        }
        if (this.handle == 0L) {
            throw new IllegalStateException("SDL_CreateGPUBuffer failed: " + MetalInterop.lastSdlError());
        }
        this.shadow = ByteBuffer.allocateDirect((int)size).order(ByteOrder.nativeOrder());
    }

    MetalGpuBuffer(MetalDevice device, int usage, ByteBuffer initial) {
        this(device, usage, initial.remaining());
        ByteBuffer src = initial.duplicate();
        this.shadow.duplicate().put(src.duplicate());
        MetalTransfers.uploadBuffer(device.handle(), this.handle, 0, src);
    }

    long handle() {
        ensureOpen();
        return this.handle;
    }

    ByteBuffer shadowSlice(long offset, long length) {
        ensureRange(offset, length);
        ByteBuffer copy = this.shadow.duplicate().order(ByteOrder.nativeOrder());
        copy.position((int)offset).limit((int)(offset + length));
        return copy.slice().order(ByteOrder.nativeOrder());
    }

    void overwriteShadow(long offset, ByteBuffer data) {
        ByteBuffer src = data.duplicate();
        ensureRange(offset, src.remaining());
        ByteBuffer dst = this.shadow.duplicate();
        dst.position((int)offset).limit((int)offset + src.remaining());
        dst.put(src);
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        ensureOpen();
        if (!read && !write) throw new IllegalArgumentException("A buffer map must request read and/or write access");
        ByteBuffer view = shadowSlice(offset, length);
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), view, () -> {
            if (write && !closed.get()) {
                MetalTransfers.uploadBuffer(device.handle(), handle, offset, shadowSlice(offset, length));
            }
        });
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            MetalInterop.sdlCall("SDL_ReleaseGPUBuffer", device.handle(), handle);
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Metal GPU buffer is closed");
    }

    private void ensureRange(long offset, long length) {
        ensureOpen();
        if (offset < 0L || length < 0L || offset + length > size()) {
            throw new IllegalArgumentException("Buffer range outside allocation: offset=" + offset + " length=" + length + " size=" + size());
        }
    }
}
