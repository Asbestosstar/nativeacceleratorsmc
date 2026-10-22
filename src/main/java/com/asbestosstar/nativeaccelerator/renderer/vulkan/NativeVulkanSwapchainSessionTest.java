package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.device.GpuSurface;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;

/** Dependency-light tests for swapchain-session state decisions and result handling. */
public final class NativeVulkanSwapchainSessionTest {
    public static void main(String[] args) {
        require(NativeVulkanSwapchainPolicy.classify(KHRSurface.VK_ERROR_SURFACE_LOST_KHR)
                        == NativeVulkanSwapchainPolicy.Result.SURFACE_LOST,
                "surface loss classification mismatch");
        require(NativeVulkanSwapchainPolicy.classify(VK10.VK_ERROR_DEVICE_LOST)
                        == NativeVulkanSwapchainPolicy.Result.DEVICE_LOST,
                "device loss classification mismatch");
        require(NativeVulkanSwapchainPolicy.presentModes(new int[]{
                        KHRSurface.VK_PRESENT_MODE_FIFO_KHR,
                        KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR})
                        .contains(GpuSurface.PresentMode.FIFO),
                "FIFO support missing");
        requireThrows(() -> NativeVulkanSwapchainPolicy.presentModes(new int[]{-1}),
                "unknown present mode accepted");
        requireThrows(() -> NativeVulkanSwapchainPolicy.chooseImageCount(0, 0, 2),
                "zero minimum image count accepted");
        System.out.println("NativeVulkanSwapchainSessionTest: PASS");
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
