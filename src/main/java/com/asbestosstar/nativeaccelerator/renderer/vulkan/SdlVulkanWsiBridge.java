package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;

import java.nio.LongBuffer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Public SDL-to-Vulkan bridge for the actual Minecraft SDL window.
 *
 * <p>The bridge deliberately does not load or unload SDL/Vulkan globally. Minecraft owns that
 * process-wide lifecycle; this class only converts SDL's extension list and creates/destroys a
 * surface for a caller-owned instance. In particular, the SDL window pointer is never reinterpreted
 * as an X11, Cocoa, Wayland, or Win32 handle.</p>
 */
public final class SdlVulkanWsiBridge {
    private SdlVulkanWsiBridge() {
    }

    /** Returns the active SDL platform's Vulkan extensions as Java-owned names. */
    public static Set<String> requiredInstanceExtensions() {
        PointerBuffer extensions = SDLVulkan.SDL_Vulkan_GetInstanceExtensions();
        if (extensions == null) {
            throw new IllegalStateException("SDL_Vulkan_GetInstanceExtensions returned null: "
                    + safeSdlError());
        }
        return extensionsFromPointers(extensions);
    }

    /** Converts SDL's null-terminated UTF-8 extension pointers without retaining native memory. */
    static Set<String> extensionsFromPointers(PointerBuffer extensions) {
        Objects.requireNonNull(extensions, "extensions");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (int index = 0; index < extensions.capacity(); index++) {
            long pointer = extensions.get(index);
            if (pointer == 0L) {
                throw new IllegalArgumentException("SDL Vulkan extension pointer was null at index " + index);
            }
            String name = extensions.getStringUTF8(index);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("SDL Vulkan extension name was blank at index " + index);
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("SDL Vulkan extension was duplicated: " + name);
            }
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("SDL Vulkan returned no instance extensions");
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(names));
    }

    /** Creates a surface for the actual SDL_Window* returned by Minecraft's Window.handle(). */
    public static long createSurface(long windowHandle, VkInstance instance) {
        if (windowHandle == 0L) throw new IllegalArgumentException("windowHandle must be non-zero");
        Objects.requireNonNull(instance, "instance");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer surface = stack.mallocLong(1);
            if (!SDLVulkan.SDL_Vulkan_CreateSurface(windowHandle, instance, null, surface)) {
                throw new IllegalStateException("SDL_Vulkan_CreateSurface failed: " + safeSdlError());
            }
            long handle = surface.get(0);
            if (handle == 0L) throw new IllegalStateException("SDL returned a null Vulkan surface");
            return handle;
        }
    }

    public static boolean presentationSupported(VkInstance instance,
                                                org.lwjgl.vulkan.VkPhysicalDevice physicalDevice,
                                                int queueFamily) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(physicalDevice, "physicalDevice");
        if (queueFamily < 0) throw new IllegalArgumentException("queueFamily must not be negative");
        return SDLVulkan.SDL_Vulkan_GetPresentationSupport(instance, physicalDevice, queueFamily);
    }

    public static void destroySurface(VkInstance instance, long surface) {
        Objects.requireNonNull(instance, "instance");
        if (surface == 0L) return;
        SDLVulkan.SDL_Vulkan_DestroySurface(instance, surface, null);
    }

    private static String safeSdlError() {
        try {
            String error = SDLError.SDL_GetError();
            return error == null || error.isBlank() ? "unknown SDL error" : error;
        } catch (RuntimeException | LinkageError ignored) {
            return "SDL error unavailable";
        }
    }
}
