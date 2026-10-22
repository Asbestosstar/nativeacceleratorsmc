package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;

/** Pure overflow and resource-shape validation tests. */
public final class NativeVulkanResourceValidationTest {
    public static void main(String[] args) {
        NativeVulkanResourceValidation.range(100, 90, 10, "buffer");
        requireThrows(() -> NativeVulkanResourceValidation.range(100, Long.MAX_VALUE, 1, "buffer"),
                "overflowing resource range accepted");
        NativeVulkanResourceValidation.map(100, 0, 100, true, false,
                GpuBuffer.USAGE_MAP_READ, true);
        requireThrows(() -> NativeVulkanResourceValidation.map(100, 0, 1, true, false, 0, true),
                "map-read without usage accepted");
        NativeVulkanResourceValidation.mipRange(3, 2, 5);
        requireThrows(() -> NativeVulkanResourceValidation.mipRange(Integer.MAX_VALUE, 2, Integer.MAX_VALUE),
                "overflowing mip range accepted");
        NativeVulkanResourceValidation.imageLayers(6, true);
        requireThrows(() -> NativeVulkanResourceValidation.imageLayers(1, true),
                "invalid cube layer count accepted");
        require(NativeVulkanResourceValidation.alignedOffset(65, 64) == 64,
                "aligned offset mismatch");
        require(NativeVulkanResourceValidation.alignedEnd(65, 64, 128) == 128,
                "aligned end mismatch");
        System.out.println("NativeVulkanResourceValidationTest: PASS");
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
