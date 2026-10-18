package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

/** Conservative support matrix for catalog display; source detection is not execution evidence. */
public final class ShaderTechnologyStatus {
    private ShaderTechnologyStatus() {}

    public static String status(ShaderTechnology technology) {
        return switch (technology) {
            case GLSL -> "FRONTEND_ONLY: exact GLSL compilation exists, general pack runtime is unavailable";
            case SPIR_V -> "UNSUPPORTED: no general shaderpack resource/runtime binding";
            case MSL -> "UNSUPPORTED: Metal shaderpack integration is not exposed";
            case ISL -> "INCOMPLETE: ISL parser, appearance semantics and runtime are unavailable";
            case SGIX -> "UNSUPPORTED: SGIX is compatibility metadata without executable state adapters";
            case CG -> "UNSUPPORTED: Cg frontend is not implemented";
            case ARB -> "UNSUPPORTED: ARB assembly frontend is not implemented";
            case UNKNOWN -> "UNKNOWN: no recognized shader technology";
        };
    }
}
