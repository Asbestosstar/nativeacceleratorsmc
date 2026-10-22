package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/** Dependency-light policy checks for the fail-closed complete-adapter gate. */
public final class NativeVulkanBackendPolicyTest {
    public static void main(String[] args) {
        String enabled = System.getProperty(NativeVulkanBackendPolicy.ENABLE_PROPERTY);
        String complete = System.getProperty("nativeaccelerator.renderer.nativeBackend.complete");
        String wsi = System.getProperty("nativeaccelerator.renderer.nativeBackend.wsi");
        try {
            System.setProperty(NativeVulkanBackendPolicy.ENABLE_PROPERTY, "off");
            System.clearProperty("nativeaccelerator.renderer.nativeBackend.complete");
            System.clearProperty("nativeaccelerator.renderer.nativeBackend.wsi");
            require(NativeVulkanBackendPolicy.mode() == NativeVulkanBackendPolicy.Mode.OFF,
                    "off mode was not recognized");
            require(!NativeVulkanBackendPolicy.explicitlyEnabled(), "disabled opt-in accepted");
            require(!NativeVulkanBackendPolicy.fallbackEnabled(), "off mode still enabled fallback");
            require(!NativeVulkanBackendPolicy.shouldOffer(), "incomplete adapter offered");

            System.setProperty(NativeVulkanBackendPolicy.ENABLE_PROPERTY, "auto");
            require(NativeVulkanBackendPolicy.mode() == NativeVulkanBackendPolicy.Mode.FALLBACK,
                    "auto mode was not recognized");
            require(NativeVulkanBackendPolicy.fallbackEnabled(), "auto mode disabled fallback");
            require(!NativeVulkanBackendPolicy.explicitlyEnabled(), "auto mode became native-first");
            require(!NativeVulkanBackendPolicy.shouldOffer(), "unimplemented adapter offered");

            System.setProperty(NativeVulkanBackendPolicy.ENABLE_PROPERTY, "native-vulkan");
            require(NativeVulkanBackendPolicy.mode() == NativeVulkanBackendPolicy.Mode.NATIVE_FIRST,
                    "native-vulkan mode was not recognized");
            require(NativeVulkanBackendPolicy.explicitlyEnabled(), "native-first request rejected");
            require(!NativeVulkanBackendPolicy.shouldOffer(), "unimplemented adapter offered");

            System.setProperty("nativeaccelerator.renderer.nativeBackend.complete", "true");
            System.setProperty("nativeaccelerator.renderer.nativeBackend.wsi", "unknown");
            require(!NativeVulkanBackendPolicy.shouldOffer(), "unknown WSI accepted");
            System.setProperty("nativeaccelerator.renderer.nativeBackend.wsi", "xlib");
            require(!NativeVulkanBackendPolicy.shouldOffer(), "fail-closed probe was bypassed by test properties");
            System.out.println("NativeVulkanBackendPolicyTest: PASS");
        } finally {
            restore(NativeVulkanBackendPolicy.ENABLE_PROPERTY, enabled);
            restore("nativeaccelerator.renderer.nativeBackend.complete", complete);
            restore("nativeaccelerator.renderer.nativeBackend.wsi", wsi);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
