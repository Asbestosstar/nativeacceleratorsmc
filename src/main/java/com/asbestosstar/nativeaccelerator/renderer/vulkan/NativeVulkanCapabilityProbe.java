package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/**
 * Conservative evidence boundary for the complete adapter.
 *
 * <p>The existing native companion exposes only an independent offscreen/URD contract. A loader or
 * physical-device count therefore cannot qualify it as a RenderPearl backend. This probe remains
 * fail-closed until the full Java/LWJGL device and platform implementations are present and covered
 * by their real-device gates.</p>
 */
public final class NativeVulkanCapabilityProbe {
    private static final NativeVulkanAdapterReadiness READINESS =
            NativeVulkanAdapterReadiness.notImplemented(
                    "complete Java/LWJGL RenderPearl device and real Minecraft WSI are not implemented yet");

    private NativeVulkanCapabilityProbe() {
    }

    /** The pre-release adapter is never offered as a partial or test-only device. */
    public static boolean isCompleteAdapterAvailable() {
        return READINESS.complete();
    }

    public static NativeVulkanAdapterReadiness readiness() {
        return READINESS;
    }

    public static String unavailabilityReason() {
        return READINESS.detail();
    }
}
