package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

/**
 * A semantic feature that a shaderpack may require from a renderer backend.
 *
 * <p>The values describe shader semantics rather than a particular graphics API.  A backend may
 * provide a capability natively, translate it, emulate it with additional work, or reject it.</p>
 */
public enum ShaderpackCapability {
    DERIVATIVES("derivatives"),
    SUBGROUP_OPERATIONS("subgroup-operations"),
    BINDLESS_RESOURCES("bindless-resources"),
    STORAGE_IMAGES("storage-images"),
    ATOMICS("atomics"),
    RAY_TRACING("ray-tracing"),
    FRAMEBUFFER_FETCH("framebuffer-fetch"),
    INPUT_ATTACHMENTS("input-attachments"),
    MSAA("msaa"),
    DEPTH_TEXTURES("depth-textures"),
    ANISOTROPIC_FILTERING("anisotropic-filtering"),
    GEOMETRY_STAGE("geometry-stage"),
    TESSELLATION("tessellation"),
    MESH_STAGE("mesh-stage"),
    INTEGER_PRECISION("integer-precision"),
    FP16("fp16"),
    FP64("fp64"),
    FRAMEBUFFER_BLENDING("framebuffer-blending"),
    TEXTURE_ARRAYS("texture-arrays"),
    SAMPLER_OBJECTS("sampler-objects"),
    SHADOW_COMPARISON("shadow-comparison"),
    STORAGE_BUFFERS("storage-buffers"),
    PUSH_CONSTANTS("push-constants"),
    COORDINATE_TRANSFORMS("coordinate-transforms");

    private final String id;

    ShaderpackCapability(String id) {
        this.id = id;
    }

    /** Stable, lower-case identifier suitable for diagnostics and serialized profiles. */
    public String id() {
        return id;
    }
}
