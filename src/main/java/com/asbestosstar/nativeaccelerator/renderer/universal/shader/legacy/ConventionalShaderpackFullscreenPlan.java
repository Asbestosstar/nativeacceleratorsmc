package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;

import java.util.List;
import java.util.Map;

/** Exact, fail-closed description of the currently supported conventional fullscreen profile. */
public record ConventionalShaderpackFullscreenPlan(
        ShaderpackDimension dimension,
        List<Program> programs,
        Map<String, String> optionValues,
        String sourceHash) {

    public record Program(String name, ShaderModule vertex, ShaderModule fragment) {
        public Program {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("program name is blank");
            java.util.Objects.requireNonNull(vertex, "vertex");
            java.util.Objects.requireNonNull(fragment, "fragment");
            if (!vertex.isValid() || !fragment.isValid()) throw new IllegalArgumentException("program modules must be valid");
            if (!"vertex".equals(vertex.source().stage()) || !"fragment".equals(fragment.source().stage())) {
                throw new IllegalArgumentException("program stage mismatch");
            }
        }
    }

    public ConventionalShaderpackFullscreenPlan {
        if (dimension == null || dimension == ShaderpackDimension.UNKNOWN) {
            throw new IllegalArgumentException("fullscreen plan requires a known dimension");
        }
        programs = List.copyOf(programs == null ? List.of() : programs);
        if (programs.size() != 1 || !programs.getFirst().name().equals("final")) {
            throw new IllegalArgumentException("fullscreen profile currently requires exactly one final program");
        }
        optionValues = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(
                optionValues == null ? Map.of() : optionValues));
        if (sourceHash == null || !sourceHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid shaderpack source hash");
        }
    }
}
