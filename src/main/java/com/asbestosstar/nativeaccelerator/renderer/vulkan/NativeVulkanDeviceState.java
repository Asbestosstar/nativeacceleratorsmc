package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle state shared by an own-device Vulkan backend and all of its resources.
 *
 * <p>This class deliberately has no Vulkan or RenderPearl dependency. Resource wrappers can use it
 * to reject work after device loss/close without keeping a raw handle alive or accidentally borrowing
 * ownership from another backend.</p>
 */
public final class NativeVulkanDeviceState {
    public enum Phase {
        OPEN,
        LOST,
        CLOSING,
        CLOSED
    }

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.OPEN);
    private volatile String lossReason;

    public Phase phase() {
        return phase.get();
    }

    public boolean isOpen() {
        return phase() == Phase.OPEN;
    }

    public String lossReason() {
        return lossReason;
    }

    public void requireOpen() {
        Phase current = phase();
        if (current != Phase.OPEN) {
            String suffix = lossReason == null || lossReason.isBlank() ? "" : ": " + lossReason;
            throw new IllegalStateException("Native Vulkan device is " + current.name().toLowerCase() + suffix);
        }
    }

    /** Marks the device lost. A closed device remains closed and is not resurrected. */
    public void markLost(String reason) {
        String detail = reason == null || reason.isBlank() ? "device lost" : reason;
        lossReason = detail;
        phase.updateAndGet(current -> current == Phase.CLOSED || current == Phase.CLOSING
                ? current : Phase.LOST);
    }

    /** Stops new work and returns whether this call won the close transition. */
    public boolean beginClose() {
        return phase.compareAndSet(Phase.OPEN, Phase.CLOSING)
                || phase.compareAndSet(Phase.LOST, Phase.CLOSING);
    }

    public void finishClose() {
        Phase current = phase.get();
        if (current != Phase.CLOSING && current != Phase.CLOSED) {
            throw new IllegalStateException("Cannot finish device close from " + current);
        }
        phase.set(Phase.CLOSED);
    }

    /** Marks a failed pre-commit construction attempt as closed without exposing a live device. */
    public void abort() {
        phase.updateAndGet(current -> current == Phase.OPEN || current == Phase.LOST
                ? Phase.CLOSED : current);
    }
}
