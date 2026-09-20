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

    static String frontFace(String pipelineName) {
        return flipGeneratedTextureVertexY(pipelineName)
                ? "SDL_GPU_FRONTFACE_CLOCKWISE"
                : "SDL_GPU_FRONTFACE_COUNTER_CLOCKWISE";
    }

    static void report(String pipelineName) {
        if (flipGeneratedTextureVertexY(pipelineName) && REPORTED.add(pipelineName)) {
            System.out.println("[Native Accelerator] Metal coordinate marker: fix31; pipeline="
                    + pipelineName + "; vertexYFlip=true; frontFace=clockwise");
        }
    }
}
