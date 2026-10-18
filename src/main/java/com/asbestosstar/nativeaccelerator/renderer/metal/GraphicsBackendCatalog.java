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
        if (choice == GraphicsApiChoice.METAL) {
            boolean supported = MetalBackend.isSupported();
            return new GraphicsBackendStatus(choice,
                    supported ? GraphicsBackendStatus.State.SUPPORTED : GraphicsBackendStatus.State.BLOCKED,
                    supported ? "SDL3 Metal probe succeeded" : MetalBackend.lastProbeReason());
        }
        if (RendererBackendRuntime.activeBackend() == RendererBackendRuntime.Backend.VULKAN) {
            return new GraphicsBackendStatus(choice, GraphicsBackendStatus.State.SUPPORTED,
                    "Minecraft/RenderPearl Vulkan device is active");
        }
        return new GraphicsBackendStatus(choice, GraphicsBackendStatus.State.UNKNOWN,
                "Vulkan availability is resolved during Minecraft device creation");
    }
}
