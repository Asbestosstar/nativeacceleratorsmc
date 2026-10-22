package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Base lifetime/ownership guard for resources owned by one Native Vulkan device. */
public abstract class NativeVulkanOwnedResource implements AutoCloseable {
    private final NativeVulkanDeviceState deviceState;
    private final AtomicBoolean closed = new AtomicBoolean();

    protected NativeVulkanOwnedResource(NativeVulkanDeviceState deviceState) {
        this.deviceState = Objects.requireNonNull(deviceState, "deviceState");
    }

    public final NativeVulkanDeviceState deviceState() {
        return deviceState;
    }

    public final boolean isClosed() {
        return closed.get();
    }

    protected final void requireUsable() {
        deviceState.requireOpen();
        if (closed.get()) throw new IllegalStateException("Native Vulkan resource is closed");
    }

    protected final boolean beginClose() {
        return closed.compareAndSet(false, true);
    }

    @Override
    public final void close() {
        if (beginClose()) closeOwnedHandle();
    }

    protected abstract void closeOwnedHandle();

    protected final void requireSameDevice(NativeVulkanOwnedResource other) {
        Objects.requireNonNull(other, "other");
        if (deviceState != other.deviceState) {
            throw new IllegalArgumentException("Native Vulkan resources belong to different devices");
        }
    }
}
