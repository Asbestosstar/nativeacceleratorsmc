package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SDL GPU buffer with a CPU shadow used by RenderPearl mapping and uniform pushes.
 *
 * <p>Mapped writes are coalesced and uploaded on the next Metal command buffer instead of creating
 * and submitting a fresh GPU command buffer for every map close. Uniform-only buffers never need a
 * GPU upload at all because the Metal backend pushes those bytes directly from this shadow.</p>
 */
final class MetalGpuBuffer extends BaseGpuBuffer {
    private final MetalDevice device;
    private final long handle;
    private ByteBuffer shadow;
    private final AtomicBoolean closed = new AtomicBoolean();

    private int dirtyStart = Integer.MAX_VALUE;
    private int dirtyEnd = -1;

    MetalGpuBuffer(MetalDevice device, int usage, long size) {
        super(usage, size);
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("SDL GPU buffer size outside Java shadow range: " + size);
        }
        this.device = device;
        Object info = MetalInterop.calloc("SDL_GPUBufferCreateInfo");
        try {
            MetalInterop.set(info, "usage", MetalConversions.bufferUsage(usage, device.nativeIndirectDrawSupported()));
            MetalInterop.set(info, "size", (int)size);
            this.handle = MetalInterop.sdlLong("SDL_CreateGPUBuffer", device.handle(), info);
        } finally {
            MetalInterop.free(info);
        }
        if (this.handle == 0L) {
            throw new IllegalStateException("SDL_CreateGPUBuffer failed: " + MetalInterop.lastSdlError());
        }
        // Large terrain vertex/index heaps are GPU-only in RenderPearl. Keeping a complete Java
        // direct-buffer mirror of every 128/32 MiB uber-buffer doubled resident memory and made
        // chunk uploads evict useful cache/pages. Allocate a shadow only for buffers that are
        // actually mapped/read by Java, used as uniforms, or decoded for CPU indirect fallback.
        this.shadow = needsCpuShadow(usage, device)
                ? ByteBuffer.allocateDirect((int)size).order(ByteOrder.nativeOrder())
                : null;
    }

    MetalGpuBuffer(MetalDevice device, int usage, ByteBuffer initial) {
        this(device, usage, initial.remaining());
        // Initial-data buffers are generally small/static (fan indices, constants). Preserve a
        // shadow even if the usage itself would otherwise be GPU-only so the first deferred upload
        // remains compatible with the normal dirty-buffer path.
        if (this.shadow == null) this.shadow = ByteBuffer.allocateDirect((int)size()).order(ByteOrder.nativeOrder());
        ByteBuffer src = initial.duplicate();
        this.shadow.duplicate().put(src);
        markDirty(0L, size());
    }

    long handle() {
        ensureOpen();
        return this.handle;
    }

    boolean hasCpuShadow() {
        return this.shadow != null;
    }

    ByteBuffer shadowSlice(long offset, long length) {
        ensureRange(offset, length);
        ByteBuffer current = this.shadow;
        if (current == null) throw new IllegalStateException("Metal buffer has no CPU shadow: usage=" + usage() + " size=" + size());
        ByteBuffer copy = current.duplicate().order(ByteOrder.nativeOrder());
        copy.position((int)offset).limit((int)(offset + length));
        return copy.slice().order(ByteOrder.nativeOrder());
    }

    void overwriteShadow(long offset, ByteBuffer data) {
        ByteBuffer src = data.duplicate();
        ensureRange(offset, src.remaining());
        ByteBuffer current = this.shadow;
        if (current == null) throw new IllegalStateException("Metal buffer has no CPU shadow for overwrite");
        ByteBuffer dst = current.duplicate();
        dst.position((int)offset).limit((int)offset + src.remaining());
        dst.put(src);
    }

    void markDirty(long offset, long length) {
        if (length <= 0 || !requiresGpuContents()) return;
        ensureRange(offset, length);
        synchronized (this) {
            int start = Math.toIntExact(offset);
            int end = Math.toIntExact(offset + length);
            dirtyStart = Math.min(dirtyStart, start);
            dirtyEnd = Math.max(dirtyEnd, end);
        }
        device.markDirty(this);
    }

    synchronized boolean hasDirtyRange() {
        return dirtyEnd > dirtyStart;
    }

    synchronized DirtyRange drainDirtyRange() {
        if (dirtyEnd <= dirtyStart) return null;
        int start = dirtyStart;
        int end = dirtyEnd;
        dirtyStart = Integer.MAX_VALUE;
        dirtyEnd = -1;
        return new DirtyRange(start, shadowSlice(start, end - start));
    }

    private static boolean needsCpuShadow(int usage, MetalDevice device) {
        int cpuVisible = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM;
        if (!device.nativeIndirectDrawSupported()) cpuVisible |= GpuBuffer.USAGE_INDIRECT_PARAMETERS;
        // Vertex/index heaps without explicit CPU visibility are the large chunk arenas.
        boolean largeGpuArenaCandidate = (usage & (GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX)) != 0;
        return !largeGpuArenaCandidate || (usage & cpuVisible) != 0;
    }

    /** Uniform buffers are consumed from the CPU shadow with SDL_PushGPU*UniformData. */
    private boolean requiresGpuContents() {
        int gpuReadUsage = GpuBuffer.USAGE_COPY_SRC
                | GpuBuffer.USAGE_VERTEX
                | GpuBuffer.USAGE_INDEX
                | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER;
        if (device.nativeIndirectDrawSupported()) gpuReadUsage |= GpuBuffer.USAGE_INDIRECT_PARAMETERS;
        return (usage() & gpuReadUsage) != 0;
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        ensureOpen();
        if (!read && !write) throw new IllegalArgumentException("A buffer map must request read and/or write access");
        ByteBuffer view = shadowSlice(offset, length);
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), view, () -> {
            if (write && !closed.get()) markDirty(offset, length);
        });
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            device.forgetDirty(this);
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

    record DirtyRange(long offset, ByteBuffer bytes) {
    }
}

