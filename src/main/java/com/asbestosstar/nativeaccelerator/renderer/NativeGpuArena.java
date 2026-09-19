package com.asbestosstar.nativeaccelerator.renderer;

import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offset suballocator intended to sit on top of a long-lived Vulkan buffer allocation. */
public final class NativeGpuArena implements AutoCloseable {
    private final RendererNativeApi api;
    private final MemorySegment handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    NativeGpuArena(RendererNativeApi api, long capacity, long defaultAlignment) {
        this.api = api;
        this.handle = api.createArena(capacity, defaultAlignment);
    }

    public long allocate(long bytes, long alignment) {
        ensureOpen();
        return api.arenaAllocate(handle, bytes, alignment);
    }

    public void free(long offset, long bytes) {
        ensureOpen();
        api.arenaFree(handle, offset, bytes);
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("GPU arena is closed");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) api.destroyArena(handle);
    }
}
