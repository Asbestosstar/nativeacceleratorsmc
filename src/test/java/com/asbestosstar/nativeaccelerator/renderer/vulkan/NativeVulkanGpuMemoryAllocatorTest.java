package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/**
 * Contract test for the real Vulkan allocator's device-independent selection rules.
 * GPU allocation is intentionally not claimed here; hosts without LWJGL native Vulkan remain
 * diagnostic-blocked in the bootstrap smoke test.
 */
public final class NativeVulkanGpuMemoryAllocatorTest {
    public static void main(String[] args) {
        // The allocator requires a live VkDevice and therefore cannot be fabricated in a plain-main
        // test. Keep this test as an explicit documentation marker until a real Vulkan harness supplies
        // VkMemoryRequirements from a device-owned buffer/image.
        System.out.println("NativeVulkanGpuMemoryAllocatorTest: BLOCKED/DIAGNOSTIC [requires real VkDevice memory requirements]");
    }
}
