package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Validated native-window evidence required before a Vulkan surface may be created.
 *
 * <p>This is deliberately data-only. It does not reinterpret an SDL/GLFW pointer as an X11 or
 * Cocoa handle and it does not create a hidden replacement window. A future platform bridge must
 * construct this record from the actual Minecraft window and provide the corresponding Vulkan
 * surface creation operation.</p>
 */
public record NativeVulkanWsiDescriptor(
        Platform platform,
        long displayHandle,
        long windowHandle,
        Set<String> requiredInstanceExtensions) {

    public static final String SURFACE_EXTENSION = "VK_KHR_surface";
    public static final String XLIB_SURFACE_EXTENSION = "VK_KHR_xlib_surface";
    public static final String XCB_SURFACE_EXTENSION = "VK_KHR_xcb_surface";
    public static final String WAYLAND_SURFACE_EXTENSION = "VK_KHR_wayland_surface";
    public static final String WIN32_SURFACE_EXTENSION = "VK_KHR_win32_surface";
    public static final String METAL_SURFACE_EXTENSION = "VK_EXT_metal_surface";

    /** Builds the SDL extension evidence before the actual window exists; surface creation remains deferred. */
    public static NativeVulkanWsiDescriptor forSdlExtensions(Set<String> extensions) {
        Objects.requireNonNull(extensions, "extensions");
        LinkedHashSet<String> copy = new LinkedHashSet<>(extensions);
        copy.add(SURFACE_EXTENSION);
        // SDL supplies the real window later; these nonzero values are evidence-only sentinels and
        // must never be passed to a platform-specific surface creation function.
        return new NativeVulkanWsiDescriptor(Platform.SDL, 1L, 1L, copy);
    }

    public NativeVulkanWsiDescriptor {
        platform = Objects.requireNonNull(platform, "platform");
        if (windowHandle == 0L) throw new IllegalArgumentException("windowHandle must be non-zero");
        if (platform.requiresDisplay() && displayHandle == 0L) {
            throw new IllegalArgumentException("displayHandle must be non-zero for " + platform);
        }
        if (requiredInstanceExtensions == null || requiredInstanceExtensions.isEmpty()) {
            throw new IllegalArgumentException("requiredInstanceExtensions must not be empty");
        }
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String extension : requiredInstanceExtensions) {
            if (extension == null || extension.isBlank()) {
                throw new IllegalArgumentException("required instance extension names must be non-blank");
            }
            if (!copy.add(extension)) throw new IllegalArgumentException("duplicate instance extension: " + extension);
        }
        if (!copy.contains(SURFACE_EXTENSION)) {
            throw new IllegalArgumentException("VK_KHR_surface is required for a Vulkan window descriptor");
        }
        if (!copy.contains(platform.surfaceExtension())) {
            throw new IllegalArgumentException("Missing required platform extension " + platform.surfaceExtension());
        }
        requiredInstanceExtensions = Set.copyOf(copy);
    }

    public enum Platform {
        XLIB(XLIB_SURFACE_EXTENSION, true),
        XCB(XCB_SURFACE_EXTENSION, true),
        WAYLAND(WAYLAND_SURFACE_EXTENSION, true),
        WIN32(WIN32_SURFACE_EXTENSION, false),
        COCOA(METAL_SURFACE_EXTENSION, false),
        GLFW(SURFACE_EXTENSION, false),
        SDL(SURFACE_EXTENSION, false);

        private final String surfaceExtension;
        private final boolean requiresDisplay;

        Platform(String surfaceExtension, boolean requiresDisplay) {
            this.surfaceExtension = surfaceExtension;
            this.requiresDisplay = requiresDisplay;
        }

        public String surfaceExtension() {
            return surfaceExtension;
        }

        public boolean requiresDisplay() {
            return requiresDisplay;
        }
    }
}
