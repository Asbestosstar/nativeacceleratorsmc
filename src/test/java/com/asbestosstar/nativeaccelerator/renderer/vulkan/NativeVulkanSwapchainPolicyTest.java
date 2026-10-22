package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.device.GpuSurface;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;

/** Dependency-light tests for swapchain policy and result classification. */
public final class NativeVulkanSwapchainPolicyTest {
    public static void main(String[] args) {
        require(NativeVulkanSwapchainPolicy.requiredSwapchainUsage(
                        VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                                | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                        == (VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                                | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT),
                "swapchain usage must retain both attachment and transfer destination needs");
        requireThrows(() -> NativeVulkanSwapchainPolicy.requiredSwapchainUsage(
                VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT),
                "surface without transfer destination cannot use the current blit path");
        requireThrows(() -> NativeVulkanSwapchainPolicy.requiredSwapchainUsage(
                VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT), "surface without color attachment was accepted");
        NativeVulkanSwapchainPolicy.Extent extent = NativeVulkanSwapchainPolicy.chooseExtent(
                1920, 100, Integer.MAX_VALUE, Integer.MAX_VALUE, 64, 64, 1280, 720);
        require(extent.width() == 1280 && extent.height() == 100, "extent clamp mismatch");
        require(NativeVulkanSwapchainPolicy.chooseImageCount(2, 3, 4) == 3, "image count clamp mismatch");
        require(NativeVulkanSwapchainPolicy.toPresentMode(KHRSurface.VK_PRESENT_MODE_FIFO_KHR)
                        == GpuSurface.PresentMode.FIFO,
                "FIFO mapping mismatch");
        require(NativeVulkanSwapchainPolicy.classify(VK10.VK_SUCCESS)
                        == NativeVulkanSwapchainPolicy.Result.SUCCESS,
                "success classification mismatch");
        require(NativeVulkanSwapchainPolicy.classify(KHRSwapchain.VK_SUBOPTIMAL_KHR)
                        == NativeVulkanSwapchainPolicy.Result.SUBOPTIMAL,
                "suboptimal classification mismatch");
        require(NativeVulkanSwapchainPolicy.classify(KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR)
                        == NativeVulkanSwapchainPolicy.Result.OUT_OF_DATE,
                "out-of-date classification mismatch");
        requireThrows(() -> NativeVulkanSwapchainPolicy.chooseExtent(
                1, 1, 0, 0, 1, 1, 2, 2), "zero fixed extent accepted");
        System.out.println("NativeVulkanSwapchainPolicyTest: PASS");
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
