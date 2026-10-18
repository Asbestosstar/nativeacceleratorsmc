package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.List;
import java.util.Map;

/** Narrow, versioned manifest understood by the first executable shaderpack profile. */
public record ShaderpackManifest(
        int format,
        String id,
        String name,
        String language,
        String profile,
        Program program,
        Pass pass) {
    public ShaderpackManifest {
        if (format <= 0) throw new IllegalArgumentException("manifest format must be positive");
        id = require(id, "id");
        name = name == null || name.isBlank() ? id : name.trim();
        language = require(language, "language").toLowerCase(java.util.Locale.ROOT);
        profile = require(profile, "profile");
        if (program == null) throw new IllegalArgumentException("program is required");
        if (pass == null) throw new IllegalArgumentException("pass is required");
    }

    public record Program(String vertex, String fragment, Map<String, String> defines) {
        public Program {
            vertex = safePath(vertex, "program.vertex");
            fragment = safePath(fragment, "program.fragment");
            defines = Map.copyOf(defines == null ? Map.of() : defines);
        }
    }

    public record Pass(String type, String output, List<String> inputs) {
        public Pass {
            type = require(type, "pass.type").toLowerCase(java.util.Locale.ROOT);
            output = require(output, "pass.output");
            inputs = List.copyOf(inputs == null ? List.of() : inputs);
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String safePath(String value, String field) {
        String path = require(value, field);
        String normalized = com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSourceProvider.normalize(path);
        if (normalized == null || !normalized.equals(path.replace('\\', '/'))) {
            throw new IllegalArgumentException(field + " is not a safe relative path");
        }
        return normalized;
    }
}
