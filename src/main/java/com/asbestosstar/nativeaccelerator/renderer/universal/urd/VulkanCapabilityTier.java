package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

/**
 * Capability tiers for the unified Vulkan renderer.
 *
 * <p>The tiers describe Native Accelerator's own contract. They are deliberately not aliases for
 * RenderPearl's Vulkan feature set: a device can support the basic conventional-render-pass path
 * while lacking RenderPearl's Vulkan 1.2 extensions and feature chain.</p>
 */
public enum VulkanCapabilityTier {
    UNAVAILABLE(0),
    BASIC(1),
    PRESENT(2),
    ENHANCED(3);

    private final int rank;

    VulkanCapabilityTier(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(VulkanCapabilityTier required) {
        return required != null && rank >= required.rank;
    }

    public boolean usable() {
        return this != UNAVAILABLE;
    }

    public static VulkanCapabilityTier highest(boolean loaderAndDevice,
                                               boolean conventionalGraphics,
                                               boolean presentation,
                                               boolean enhanced) {
        if (!loaderAndDevice || !conventionalGraphics) return UNAVAILABLE;
        if (enhanced && presentation) return ENHANCED;
        if (presentation) return PRESENT;
        return BASIC;
    }
}
