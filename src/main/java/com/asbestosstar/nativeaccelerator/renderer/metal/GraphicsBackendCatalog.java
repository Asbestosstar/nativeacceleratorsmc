package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;

import java.util.Arrays;
import java.util.List;

/** Central metadata for the executable backend selector; historical shader technologies stay separate. */
public final class GraphicsBackendCatalog {
    private GraphicsBackendCatalog() {}

    public static List<GraphicsBackendStatus> statuses() {
        return Arrays.stream(GraphicsApiChoice.values()).map(GraphicsBackendCatalog::status).toList();
    }

    public static GraphicsBackendStatus status(GraphicsApiChoice choice) {
        if (choice == null || choice == GraphicsApiChoice.DEFAULT) {
            return new GraphicsBackendStatus(GraphicsApiChoice.DEFAULT,
                    GraphicsBackendStatus.State.SUPPORTED, "Minecraft default backend ordering");
        }
        if (choice == GraphicsApiChoice.OPENGL) {
            return new GraphicsBackendStatus(choice, GraphicsBackendStatus.State.SUPPORTED,
                    "Minecraft/RenderPearl OpenGL backend");
        }
        if (choice == GraphicsApiChoice.NATIVE_VULKAN
                && !com.asbestosstar.nativeaccelerator.renderer.vulkan.NativeVulkanBackendPolicy.shouldOffer()) {
            return new GraphicsBackendStatus(choice, GraphicsBackendStatus.State.BLOCKED,
                    "Native Vulkan unavailable: "
                            + com.asbestosstar.nativeaccelerator.renderer.vulkan.NativeVulkanBackendPolicy.unavailableReason());
        }
        if (choice == GraphicsApiChoice.METAL) {
            boolean supported = MetalBackend.isSupported();
            return new GraphicsBackendStatus(choice,
                    supported ? GraphicsBackendStatus.State.SUPPORTED : GraphicsBackendStatus.State.BLOCKED,
                    supported ? "SDL3 Metal probe succeeded" : MetalBackend.lastProbeReason());
        }
        if (choice == GraphicsApiChoice.VULKAN || choice == GraphicsApiChoice.NATIVE_VULKAN) {
            boolean nativeAvailable = com.asbestosstar.nativeaccelerator.renderer.vulkan.NativeVulkanBackendPolicy.shouldOffer();
            boolean officialActive = RendererBackendRuntime.activeBackend() == RendererBackendRuntime.Backend.VULKAN;
            if (choice == GraphicsApiChoice.VULKAN && officialActive) {
                return new GraphicsBackendStatus(choice, GraphicsBackendStatus.State.SUPPORTED,
                        "Minecraft/RenderPearl Vulkan device is active"
                                + (nativeAvailable ? "; Native Vulkan remains an eligible fallback" : ""));
            }
            if (nativeAvailable) {
                String position = com.asbestosstar.nativeaccelerator.renderer.vulkan.NativeVulkanBackendPolicy
                        .explicitNativeFirst() ? "primary candidate" : "fallback candidate";
                return new GraphicsBackendStatus(choice, GraphicsBackendStatus.State.SUPPORTED,
                        "Native Vulkan RenderPearl adapter is eligible as a " + position);
            }
            return new GraphicsBackendStatus(choice, GraphicsBackendStatus.State.UNKNOWN,
                    "Native Vulkan unavailable: "
                            + com.asbestosstar.nativeaccelerator.renderer.vulkan.NativeVulkanBackendPolicy.unavailableReason()
                            + (officialActive ? "; official Vulkan is active" : "; official Vulkan is resolved during device creation"));
        }
        return new GraphicsBackendStatus(choice, GraphicsBackendStatus.State.UNKNOWN,
                "Vulkan availability is resolved during Minecraft device creation");
    }
}
