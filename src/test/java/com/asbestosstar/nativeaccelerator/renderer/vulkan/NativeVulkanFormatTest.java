package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.GpuFormat;

/** Pure format mapping test; it does not create a Vulkan instance or claim GPU support. */
public final class NativeVulkanFormatTest {
    public static void main(String[] args) {
        require(NativeVulkanFormat.vkFormat(GpuFormat.RGBA8_UNORM) != 0, "RGBA8 mapping missing");
        require(NativeVulkanFormat.vkFormat(GpuFormat.D32_FLOAT) != 0, "depth mapping missing");
        require(NativeVulkanFormat.vkFormat(GpuFormat.RG11B10_FLOAT) != 0, "packed mapping missing");
        System.out.println("NativeVulkanFormatTest: PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
