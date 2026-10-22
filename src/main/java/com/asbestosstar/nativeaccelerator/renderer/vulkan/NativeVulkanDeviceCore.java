package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import java.util.Objects;

/** A presentation-independent Vulkan core device plus a deferred, real-window WSI attachment. */
public final class NativeVulkanDeviceCore implements AutoCloseable {
    private final NativeVulkanDeviceBootstrap bootstrap;
    private final NativeVulkanGpuMemoryAllocator allocator;
    private final NativeVulkanDeviceState state;
    private boolean closed;

    public NativeVulkanDeviceCore(NativeVulkanDeviceBootstrap bootstrap, NativeVulkanDeviceState state) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.state = Objects.requireNonNull(state, "state");
        this.allocator = new NativeVulkanGpuMemoryAllocator(bootstrap);
    }

    static NativeVulkanDeviceCore fromOwnedWsi(NativeVulkanOwnedWsiBootstrap wsi,
                                                NativeVulkanDeviceState state) {
        Objects.requireNonNull(wsi, "wsi");
        NativeVulkanDeviceBootstrap bootstrap = wsi.deviceBootstrap();
        try {
            return new NativeVulkanDeviceCore(bootstrap, state);
        } catch (Throwable failure) {
            bootstrap.close();
            throw failure;
        }
    }
    public synchronized NativeVulkanDeviceBootstrap bootstrap() {
        requireOpen();
        return bootstrap;
    }

    public synchronized NativeVulkanGpuMemoryAllocator allocator() {
        requireOpen();
        return allocator;
    }

    public NativeVulkanDeviceState state() { return state; }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Vulkan device core is closed");
        state.requireOpen();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        Throwable first = null;
        try { allocator.close(); } catch (Throwable failure) { first = failure; }
        try { bootstrap.close(); } catch (Throwable failure) {
            if (first == null) first = failure;
            else first.addSuppressed(failure);
        }
        if (first != null) throw new IllegalStateException("Vulkan device core cleanup failed", first);
    }
}
