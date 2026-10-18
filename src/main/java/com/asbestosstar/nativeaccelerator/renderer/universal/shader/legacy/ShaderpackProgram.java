package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.List;

/** Immutable conventional shaderpack program discovered from standard file names. */
public record ShaderpackProgram(String name, List<String> stages) {
    public ShaderpackProgram {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("program name is blank");
        stages = List.copyOf(stages == null ? List.of() : stages);
    }

    public boolean hasStage(String extension) {
        return stages.stream().anyMatch(value -> value.endsWith(extension));
    }

    public boolean hasCombinedSource() {
        return hasStage(".glsl");
    }

    public String familyName() {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    public boolean isWorldVariant() {
        return name.startsWith("world") || familyName().startsWith("gbuffers_")
                || familyName().startsWith("shadow") || familyName().startsWith("deferred");
    }
}

