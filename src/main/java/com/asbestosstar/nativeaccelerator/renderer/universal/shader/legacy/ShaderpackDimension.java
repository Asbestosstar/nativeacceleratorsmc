package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

/** Conventional shaderpack dimension selected by a world wrapper directory. */
public enum ShaderpackDimension {
    OVERWORLD,
    NETHER,
    END,
    UNKNOWN;

    public static ShaderpackDimension fromPath(String path) {
        String value = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("world0/") || value.equals("world0")) return OVERWORLD;
        if (value.startsWith("world-1/") || value.equals("world-1")) return NETHER;
        if (value.startsWith("world1/") || value.equals("world1")) return END;
        return UNKNOWN;
    }

    public String define() {
        return switch (this) {
            case OVERWORLD -> "OVERWORLD";
            case NETHER -> "NETHER";
            case END -> "END";
            case UNKNOWN -> "";
        };
    }
}
