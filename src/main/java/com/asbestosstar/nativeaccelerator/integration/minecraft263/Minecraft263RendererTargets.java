package com.asbestosstar.nativeaccelerator.integration.minecraft263;

import java.util.List;

/**
 * 26.3-pre-2 renderer interception map. Names are kept as data so the independent
 * renderer core never links directly against Minecraft or a specific mod loader.
 */
public final class Minecraft263RendererTargets {
    private Minecraft263RendererTargets() {}

    public static final String FRONTEND_COMMAND_ENCODER = "com.mojang.renderpearl.frontend.FrontendCommandEncoder";
    public static final String FRONTEND_RENDER_PASS = "com.mojang.renderpearl.frontend.FrontendRenderPass";
    public static final String STAGED_VERTEX_BUFFER = "com.mojang.blaze3d.vertex.StagedVertexBuffer";
    public static final String BUFFER_BUILDER = "com.mojang.blaze3d.vertex.BufferBuilder";
    public static final String MESH_DATA = "com.mojang.blaze3d.vertex.MeshData";
    public static final String RENDER_SYSTEM = "com.mojang.blaze3d.systems.RenderSystem";
    public static final String GPU_DEVICE_BACKEND = "com.mojang.renderpearl.backend.api.GpuDeviceBackend";
    public static final String RENDER_PASS_BACKEND = "com.mojang.renderpearl.backend.api.RenderPassBackend";
    public static final String COMMAND_ENCODER_BACKEND = "com.mojang.renderpearl.backend.api.CommandEncoderBackend";

    public static final List<Target> VULKAN_REWRITE = List.of(
            new Target(FRONTEND_COMMAND_ENCODER, "submit/createRenderPass/copy/upload", "bridge command lifetime into native renderer"),
            new Target(FRONTEND_RENDER_PASS, "pipeline/uniform/buffer/draw methods", "batch state and emit native Vulkan commands"),
            new Target(STAGED_VERTEX_BUFFER, "appendDraw/upload", "persistent arena upload and section residency"),
            new Target(BUFFER_BUILDER, "bulk terrain construction paths", "replace fine-grained vertex emission with native section batches"),
            new Target(MESH_DATA, "sorting/index generation", "native or GPU draw-order generation"),
            new Target(RENDER_SYSTEM, "device/surface initialization seam", "select independent Vulkan renderer when enabled")
    );

    public record Target(String className, String methods, String role) {}
}
