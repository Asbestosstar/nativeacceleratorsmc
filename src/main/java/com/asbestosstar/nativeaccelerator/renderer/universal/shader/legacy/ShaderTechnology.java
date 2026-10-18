package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.Locale;

/** Shader/API families recognized by the legacy shaderpack catalog. */
public enum ShaderTechnology {
    GLSL("GLSL", "GLSL shader source", false),
    SPIR_V("SPIR-V", "SPIR-V module", false),
    MSL("MSL", "Metal Shading Language", false),
    ISL("ISL", "ISL 3.0 (foundation only)", true),
    SGIX("SGIX", "SGIX OpenGL compatibility", true),
    CG("Cg", "Cg (frontend unavailable)", true),
    ARB("ARB", "ARB assembly (frontend unavailable)", true),
    UNKNOWN("Unknown", "Unrecognized shader technology", true);

    private final String label;
    private final String description;
    private final boolean incomplete;

    ShaderTechnology(String label, String description, boolean incomplete) {
        this.label = label;
        this.description = description;
        this.incomplete = incomplete;
    }

    public String label() { return label; }
    public String description() { return description; }
    public boolean incomplete() { return incomplete; }

    public static ShaderTechnology fromFileName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".spv") || lower.endsWith(".spirv")) return SPIR_V;
        if (lower.endsWith(".metal") || lower.endsWith(".msl")) return MSL;
        if (lower.endsWith(".isl")) return ISL;
        if (lower.endsWith(".cg") || lower.endsWith(".cgfx")) return CG;
        if (lower.endsWith(".arb") || lower.endsWith(".vp") || lower.endsWith(".fp")) return ARB;
        if (lower.endsWith(".glsl") || lower.endsWith(".vert") || lower.endsWith(".frag")
                || lower.endsWith(".vsh") || lower.endsWith(".fsh") || lower.endsWith(".geom")
                || lower.endsWith(".comp")) return GLSL;
        return UNKNOWN;
    }
}
