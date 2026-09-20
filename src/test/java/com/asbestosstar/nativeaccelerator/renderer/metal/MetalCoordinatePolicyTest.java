package com.asbestosstar.nativeaccelerator.renderer.metal;

public final class MetalCoordinatePolicyTest {
    public static void main(String[] args) {
        System.clearProperty(MetalCoordinatePolicy.PROPERTY);

        require(MetalCoordinatePolicy.flipGeneratedTextureVertexY("minecraft:pipeline/lightmap"));
        require(MetalCoordinatePolicy.flipGeneratedTextureVertexY("minecraft:pipeline/animate_sprite_blit"));
        require(MetalCoordinatePolicy.flipGeneratedTextureVertexY("minecraft:pipeline/animate_sprite_interpolate"));

        require(!MetalCoordinatePolicy.flipGeneratedTextureVertexY("minecraft:pipeline/gui_textured"));
        require(!MetalCoordinatePolicy.flipGeneratedTextureVertexY("minecraft:pipeline/panorama"));
        require(!MetalCoordinatePolicy.flipGeneratedTextureVertexY("minecraft:pipeline/solid_terrain"));

        require("SDL_GPU_FRONTFACE_CLOCKWISE".equals(
                MetalCoordinatePolicy.frontFace("minecraft:pipeline/lightmap")));
        require("SDL_GPU_FRONTFACE_COUNTER_CLOCKWISE".equals(
                MetalCoordinatePolicy.frontFace("minecraft:pipeline/panorama")));

        System.setProperty(MetalCoordinatePolicy.PROPERTY, "false");
        require(!MetalCoordinatePolicy.flipGeneratedTextureVertexY("minecraft:pipeline/lightmap"));
        require("SDL_GPU_FRONTFACE_COUNTER_CLOCKWISE".equals(
                MetalCoordinatePolicy.frontFace("minecraft:pipeline/lightmap")));

        System.out.println("MetalCoordinatePolicyTest: PASS");
    }

    private static void require(boolean value) {
        if (!value) throw new AssertionError();
    }
}
