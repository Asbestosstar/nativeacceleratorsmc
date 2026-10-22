package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import java.util.Objects;

/** Immutable evidence matrix for the complete RenderPearl adapter activation gate. */
public record NativeVulkanAdapterReadiness(
        boolean loader,
        boolean instance,
        boolean physicalDevice,
        boolean logicalDevice,
        boolean graphicsQueue,
        boolean presentQueue,
        boolean resources,
        boolean commands,
        boolean pipelines,
        boolean synchronization,
        boolean surface,
        boolean swapchain,
        boolean acquireRenderPresent,
        boolean teardown,
        String detail) {

    public NativeVulkanAdapterReadiness {
        detail = Objects.requireNonNullElse(detail, "");
    }

    /** Every mandatory gate must have real evidence before Minecraft may see the candidate. */
    public boolean complete() {
        return loader && instance && physicalDevice && logicalDevice && graphicsQueue
                && presentQueue && resources && commands && pipelines && synchronization
                && surface && swapchain && acquireRenderPresent && teardown;
    }

    public static NativeVulkanAdapterReadiness notImplemented(String detail) {
        return new NativeVulkanAdapterReadiness(
                false, false, false, false, false, false, false, false, false, false,
                false, false, false, false, detail);
    }
}
