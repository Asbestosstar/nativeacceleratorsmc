package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinate compatibility policy for Vulkan-target SPIR-V translated to MSL.
 *
 * <p>Minecraft 26.3 compiles its GLSL to Vulkan-target SPIR-V. Most ordinary scene/UI paths are
 * already correct on the fix30 Metal baseline, so do not apply a global Y flip. The generated
 * texture passes below render directly into textures that Minecraft later samples with Vulkan/
 * RenderPearl coordinates. They are the narrowly-scoped compatibility test for Metal.</p>
 */
final class MetalCoordinatePolicy {
    static final String PROPERTY = "nativeaccelerator.renderer.metal.generatedTextureYFlip";

    /**
     * Some RenderPearl passes intentionally render ordinary scene/item pipelines into textures
     * which are sampled later using Vulkan framebuffer conventions.  Pipeline name alone cannot
     * describe that state: GuiItemAtlas, for example, uses the same item pipelines as normal scene
     * rendering.  Keep target semantics explicit so the Metal backend can select an equivalent
     * raster/shader variant without changing ordinary world rendering.
     */
    enum TargetMode {
        DEFAULT,
        MAIN_TARGET,
        GUI_ITEM_ATLAS
    }

    record Rect(int x, int y, int width, int height) {}

    private static final Set<String> GENERATED_TEXTURE_PIPELINES = Set.of(
            "minecraft:pipeline/animate_sprite_blit",
            "minecraft:pipeline/animate_sprite_interpolate",
            "minecraft:pipeline/lightmap"
    );

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private MetalCoordinatePolicy() {}

    static boolean flipGeneratedTextureVertexY(String pipelineName) {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, "true"))
                && GENERATED_TEXTURE_PIPELINES.contains(pipelineName);
    }

    static boolean targetNeedsVertexYFlip(TargetMode mode) {
        return mode == TargetMode.GUI_ITEM_ATLAS;
    }

    static String frontFace(String pipelineName) {
        return frontFace(pipelineName, TargetMode.DEFAULT);
    }

    static String frontFace(String pipelineName, TargetMode mode) {
        // A clip-space Y flip reverses winding.  The GUI item atlas is target-dependent and cannot
        // be identified by pipeline name, so its dedicated shader variant gets the matching winding
        // correction here.
        boolean flipped = flipGeneratedTextureVertexY(pipelineName) || targetNeedsVertexYFlip(mode);
        return flipped ? "SDL_GPU_FRONTFACE_CLOCKWISE" : "SDL_GPU_FRONTFACE_COUNTER_CLOCKWISE";
    }

    static TargetMode classifyTarget(String textureLabel) {
        if ("UI items atlas".equals(textureLabel)) return TargetMode.GUI_ITEM_ATLAS;
        if (textureLabel != null && textureLabel.startsWith("Main / Color")) return TargetMode.MAIN_TARGET;
        return TargetMode.DEFAULT;
    }

    /**
     * RenderPearl GUI code submits scissor rectangles in bottom-origin framebuffer coordinates
     * (GuiRenderer converts top-left GUI rectangles using windowHeight - bottom). SDL GPU / Metal
     * scissor rectangles are top-left-origin. Convert only the main presentation target, which receives GuiRenderer's bottom-origin framebuffer rectangles.
     * GuiItemAtlas constructs its own atlas-space scissor coordinates and must not be flipped again;
     * doing so clips item draws out of their assigned atlas slots. Leave all offscreen targets unchanged.
     */
    static Rect scissorRect(TargetMode mode, int targetHeight, int x, int y, int width, int height) {
        boolean flip = mode == TargetMode.MAIN_TARGET;
        int metalY = flip ? targetHeight - (y + height) : y;
        return new Rect(x, metalY, width, height);
    }

    static void report(String pipelineName) {
        if (flipGeneratedTextureVertexY(pipelineName) && REPORTED.add(pipelineName)) {
            System.out.println("[Native Accelerator] Metal coordinate marker: fix31; pipeline="
                    + pipelineName + "; vertexYFlip=true; frontFace=clockwise");
        }
    }
}
