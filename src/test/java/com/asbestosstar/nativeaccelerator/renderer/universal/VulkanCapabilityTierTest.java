package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.urd.VulkanCapabilityTier;

/** Pure checks for the unified Vulkan tier lattice; no Vulkan loader is required. */
public final class VulkanCapabilityTierTest {
    public static void main(String[] args) {
        require(VulkanCapabilityTier.highest(false, true, true, true)
                        == VulkanCapabilityTier.UNAVAILABLE, "loader/device absence");
        require(VulkanCapabilityTier.highest(true, false, true, true)
                        == VulkanCapabilityTier.UNAVAILABLE, "graphics contract absence");
        require(VulkanCapabilityTier.highest(true, true, false, true)
                        == VulkanCapabilityTier.BASIC, "offscreen basic tier");
        require(VulkanCapabilityTier.highest(true, true, true, false)
                        == VulkanCapabilityTier.PRESENT, "present tier");
        require(VulkanCapabilityTier.highest(true, true, true, true)
                        == VulkanCapabilityTier.ENHANCED, "enhanced tier");
        require(VulkanCapabilityTier.PRESENT.atLeast(VulkanCapabilityTier.BASIC), "tier ordering");
        require(!VulkanCapabilityTier.BASIC.atLeast(VulkanCapabilityTier.PRESENT), "tier ordering reverse");
        System.out.println("VulkanCapabilityTierTest: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
