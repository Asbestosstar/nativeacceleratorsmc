package com.asbestosstar.nativeaccelerator.renderer;

import com.asbestosstar.nativeaccelerator.renderer.metal.MetalBackend;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records the backend Minecraft actually succeeded in creating and starts only backend-specific
 * Native Accelerator renderer services that can benefit that backend.
 *
 * <p>This is deliberately later than ordinary mod initialization. Merely loading Native Accelerator
 * must not probe Vulkan, create a Vulkan companion context, or touch a Vulkan loader when Minecraft
 * is running OpenGL or Metal. Likewise a dedicated/server-only process never reaches this client
 * hook at all.</p>
 */
public final class RendererBackendRuntime {
    public enum Backend {
        NONE,
        OPENGL,
        VULKAN,
        METAL,
        OTHER
    }

    private static final AtomicReference<Backend> ACTIVE = new AtomicReference<>(Backend.NONE);

    private RendererBackendRuntime() {
    }

    public static Backend activeBackend() {
        return ACTIVE.get();
    }

    public static void backendCreated(GpuBackend backend) {
        Backend kind = classify(backend);
        ACTIVE.set(kind);
        System.out.println("[Native Accelerator] Minecraft graphics backend created: " + kind.name().toLowerCase(Locale.ROOT)
                + " (" + backend.getName() + ")");

        // The independent native Vulkan renderer is strictly Vulkan-only. In particular, do not even
        // ask RendererPlatformPolicy to load/probe its native companion while Metal/OpenGL is active.
        if (kind != Backend.VULKAN) {
            return;
        }
        if (serverOnlyRendererMode()) {
            System.out.println("[Native Accelerator] Vulkan backend is active, but Native Accelerator renderer role is server-only; "
                    + "skipping Vulkan companion initialization.");
            return;
        }

        RendererPlatformPolicy.adoptLoaderGameDirectory();
        NativeVulkanRenderer.initializeIfEnabled();
    }

    private static Backend classify(GpuBackend backend) {
        if (backend instanceof MetalBackend) return Backend.METAL;
        if (backend instanceof VulkanBackend) return Backend.VULKAN;
        if (backend instanceof GlBackend) return Backend.OPENGL;
        return Backend.OTHER;
    }

    private static boolean serverOnlyRendererMode() {
        String value = System.getProperty(RendererPlatformPolicy.ROLE_PROPERTY, "")
                .trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "server", "off", "server-only", "force-server" -> true;
            default -> false;
        };
    }
}

