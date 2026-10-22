package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import java.util.Arrays;

/** Immutable queue-family evidence captured before a Vulkan surface exists. */
public record NativeVulkanQueueFamilies(int[] queueFlags, int[] queueCounts) {
    public NativeVulkanQueueFamilies {
        if (queueFlags == null || queueCounts == null
                || queueFlags.length == 0 || queueFlags.length != queueCounts.length) {
            throw new IllegalArgumentException("Queue-family evidence arrays must have equal non-zero lengths");
        }
        queueFlags = queueFlags.clone();
        queueCounts = queueCounts.clone();
        for (int count : queueCounts) {
            if (count < 0) throw new IllegalArgumentException("Queue-family counts must not be negative");
        }
    }

    @Override
    public int[] queueFlags() {
        return queueFlags.clone();
    }

    @Override
    public int[] queueCounts() {
        return queueCounts.clone();
    }

    public int size() {
        return queueFlags.length;
    }

    @Override
    public String toString() {
        return "NativeVulkanQueueFamilies[queueFlags=" + Arrays.toString(queueFlags)
                + ", queueCounts=" + Arrays.toString(queueCounts) + ']';
    }
}
