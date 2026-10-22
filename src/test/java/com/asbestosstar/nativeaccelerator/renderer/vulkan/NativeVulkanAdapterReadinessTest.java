package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/** Ensures partial/offscreen evidence cannot satisfy the complete-adapter gate. */
public final class NativeVulkanAdapterReadinessTest {
    public static void main(String[] args) {
        NativeVulkanAdapterReadiness partial = new NativeVulkanAdapterReadiness(
                true, true, true, true, true, false, true, true, true, true,
                false, false, false, true, "no real WSI");
        require(!partial.complete(), "partial readiness was accepted");
        require(!NativeVulkanCapabilityProbe.isCompleteAdapterAvailable(),
                "production probe accepted incomplete adapter");
        require(!NativeVulkanCapabilityProbe.readiness().complete(),
                "production readiness unexpectedly complete");
        System.out.println("NativeVulkanAdapterReadinessTest: PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
