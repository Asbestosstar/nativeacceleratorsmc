package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;

import java.lang.foreign.MemorySegment;

/**
 * Unified-renderer backend descriptor for a Native Accelerator Vulkan session.
 *
 * <p>The session ABI is optional. An unavailable companion is reported as a blocked backend rather
 * than being confused with a RenderPearl-owned device.</p>
 */
public final class NativeVulkanUrdBackend implements UrdBackend {
    private final RendererNativeApi api;
    private final VulkanCapabilityTier tier;
    private final String reason;

    public NativeVulkanUrdBackend(RendererNativeApi api, VulkanCapabilityTier tier, String reason) {
        this.api = api;
        this.tier = tier == null ? VulkanCapabilityTier.UNAVAILABLE : tier;
        this.reason = reason == null ? "" : reason;
    }

    @Override
    public String name() {
        return "native-vulkan-" + tier.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public UrdCapabilities capabilities() {
        java.util.LinkedHashSet<String> features = new java.util.LinkedHashSet<>();
        if (tier.usable()) {
            features.add("vertex-fragment");
            features.add("rgba8-render-target");
            features.add("classic-render-pass");
            features.add("cpu-cull");
            features.add("offscreen");
        }
        if (tier.atLeast(VulkanCapabilityTier.PRESENT)) features.add("present");
        if (tier == VulkanCapabilityTier.ENHANCED) {
            features.add("draw-indirect");
            features.add("gpu-cull");
        }
        return new UrdCapabilities(name(), features, 1, false);
    }

    public UrdBackendOwnership ownership() {
        return UrdBackendOwnership.NATIVE_VULKAN;
    }

    public VulkanCapabilityTier tier() {
        return tier;
    }

    public String reason() {
        return reason;
    }

    @Override
    public UrdDevice createDevice() {
        if (!tier.usable()) throw new UrdValidationException("native Vulkan backend unavailable: " + reason);
        if (api == null) throw new UrdValidationException("native Vulkan backend has no renderer API: " + reason);
        if (!api.vulkanSessionSupported()) {
            throw new UrdValidationException("native Vulkan session ABI not present in this library build");
        }
        // VULKAN_MODE_OFFSCREEN = 1; use a 1x1 probe extent — real swapchain extent is
        // set by the render pass rather than the session.
        MemorySegment sessionHandle = api.vulkanSessionCreate(
                RendererNativeApi.VULKAN_MODE_OFFSCREEN, 1, 1);
        return new NativeVulkanUrdDevice(api, sessionHandle, name());
    }
}
