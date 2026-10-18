package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.List;
import java.util.Map;

/** Loader-neutral conventional stage entrypoint before backend resource ownership. */
public record ShaderpackStageVariant(
        String program,
        ShaderpackDimension dimension,
        String stage,
        String wrapperPath,
        Map<String, String> defines,
        List<ShaderpackRenderTarget> renderTargets) {
    public ShaderpackStageVariant {
        program = require(program, "program");
        dimension = dimension == null ? ShaderpackDimension.UNKNOWN : dimension;
        stage = require(stage, "stage").toLowerCase(java.util.Locale.ROOT);
        wrapperPath = require(wrapperPath, "wrapperPath");
        defines = Map.copyOf(defines == null ? Map.of() : defines);
        renderTargets = List.copyOf(renderTargets == null ? List.of() : renderTargets);
    }

    public String key() {
        return dimension.name().toLowerCase(java.util.Locale.ROOT) + "/" + program + "/" + stage;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value;
    }
}
