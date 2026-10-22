package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import java.util.Set;

/** Pure validation tests for real-window Vulkan evidence. */
public final class NativeVulkanWsiDescriptorTest {
    public static void main(String[] args) {
        NativeVulkanWsiDescriptor descriptor = new NativeVulkanWsiDescriptor(
                NativeVulkanWsiDescriptor.Platform.XLIB, 11L, 22L,
                Set.of(NativeVulkanWsiDescriptor.SURFACE_EXTENSION,
                        NativeVulkanWsiDescriptor.XLIB_SURFACE_EXTENSION));
        require(descriptor.platform() == NativeVulkanWsiDescriptor.Platform.XLIB,
                "platform was not retained");
        requireThrows(() -> new NativeVulkanWsiDescriptor(
                NativeVulkanWsiDescriptor.Platform.XLIB, 0L, 22L,
                Set.of(NativeVulkanWsiDescriptor.SURFACE_EXTENSION,
                        NativeVulkanWsiDescriptor.XLIB_SURFACE_EXTENSION)),
                "zero display handle accepted");
        requireThrows(() -> new NativeVulkanWsiDescriptor(
                NativeVulkanWsiDescriptor.Platform.XLIB, 11L, 0L,
                Set.of(NativeVulkanWsiDescriptor.SURFACE_EXTENSION,
                        NativeVulkanWsiDescriptor.XLIB_SURFACE_EXTENSION)),
                "zero window handle accepted");
        requireThrows(() -> new NativeVulkanWsiDescriptor(
                NativeVulkanWsiDescriptor.Platform.XLIB, 11L, 22L,
                Set.of(NativeVulkanWsiDescriptor.SURFACE_EXTENSION)),
                "missing platform extension accepted");
        System.out.println("NativeVulkanWsiDescriptorTest: PASS");
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
