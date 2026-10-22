package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import java.util.OptionalDouble;

/** Pure sampler capability and LOD policy shared by the Vulkan wrapper and tests. */
final class NativeVulkanSamplerPolicy {
    private NativeVulkanSamplerPolicy() {
    }

    static int validateAnisotropy(int requested, int supported) {
        if (requested < 1) throw new IllegalArgumentException("maxAnisotropy must be at least one");
        if (supported < 1) throw new IllegalArgumentException("supported anisotropy must be at least one");
        if (requested > supported) {
            throw new UnsupportedOperationException("Requested sampler anisotropy " + requested
                    + " exceeds negotiated Vulkan capability " + supported);
        }
        return requested;
    }

    static double effectiveMaxLod(OptionalDouble requested) {
        if (requested == null) throw new NullPointerException("maxLod");
        if (requested.isPresent()
                && (!Double.isFinite(requested.getAsDouble()) || requested.getAsDouble() < 0.0)) {
            throw new IllegalArgumentException("maxLod must be finite and non-negative");
        }
        return requested.orElse(1000.0);
    }

    static boolean usesLinearMipmap(OptionalDouble requested) {
        return effectiveMaxLod(requested) > 0.25;
    }

    static double vkMaxLod(OptionalDouble requested) {
        return Math.max(0.25, effectiveMaxLod(requested));
    }
}
