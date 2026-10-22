package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import java.util.OptionalDouble;

/** Pure sampler policy tests; no Vulkan loader or device is required. */
public final class NativeVulkanSamplerPolicyTest {
    public static void main(String[] args) {
        require(NativeVulkanSamplerPolicy.validateAnisotropy(1, 1) == 1,
                "supported anisotropy was rejected");
        requireThrows(() -> NativeVulkanSamplerPolicy.validateAnisotropy(2, 1),
                "unsupported anisotropy was accepted");
        require(!NativeVulkanSamplerPolicy.usesLinearMipmap(OptionalDouble.of(0.25)),
                "quarter-LOD sampler used linear mipmapping");
        require(NativeVulkanSamplerPolicy.usesLinearMipmap(OptionalDouble.of(1.0)),
                "multi-LOD sampler used nearest mipmapping");
        require(NativeVulkanSamplerPolicy.vkMaxLod(OptionalDouble.of(0.0)) == 0.25,
                "Vulkan max LOD was below the RenderPearl floor");
        requireThrows(() -> NativeVulkanSamplerPolicy.effectiveMaxLod(OptionalDouble.of(-1.0)),
                "negative max LOD was accepted");
        System.out.println("NativeVulkanSamplerPolicyTest: PASS");
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException | UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
