package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.util.Set;

/** Tests truthful Vulkan-to-RenderPearl metadata conversion without requiring a Vulkan loader. */
public final class NativeVulkanDeviceInfoTest {
    public static void main(String[] args) {
        try {
            run();
        } catch (LinkageError unavailable) {
            System.out.println("NativeVulkanDeviceInfoTest: BLOCKED/DIAGNOSTIC ["
                    + unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage() + "]");
        }
    }

    private static void run() {
        VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc();
        try {
            var info = NativeVulkanDeviceInfo.fromProperties(properties,
                    Set.of("VK_KHR_shader_draw_parameters", "VK_KHR_draw_indirect_count"));
            require(info.name().equals("Vulkan device"), "device name fallback mismatch");
            require(info.vendorName().startsWith("PCI vendor"), "vendor fallback mismatch");
            require(info.limits().maxTextureSize() == 1, "texture limit fallback mismatch");
            require(info.limits().maxAnisotropy() == 1, "anisotropy was over-advertised");
            require(info.features().shaderDrawParameters(), "extension feature missing");
            require(info.features().drawIndirect(), "core draw indirect missing");
            require(!info.features().multiDrawIndirect(), "unsupported multi-draw was advertised");
            require(info.timestampPeriod() == 1.0f, "timestamp period fallback mismatch");
            System.out.println("NativeVulkanDeviceInfoTest: PASS");
        } finally {
            properties.free();
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
