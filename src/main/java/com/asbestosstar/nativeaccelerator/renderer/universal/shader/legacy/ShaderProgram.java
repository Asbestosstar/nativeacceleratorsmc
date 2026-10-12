package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable named program declaration and optional stage references. */
public record ShaderProgram(String id, Map<String, String> stages) {
    public ShaderProgram {
        id = GraphResource.requireId(id, "program id");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        if (stages != null) stages.forEach((stage, source) -> {
            if (stage == null || stage.isBlank() || source == null || source.isBlank())
                throw new IllegalArgumentException("blank shader stage or source");
            String key = stage.trim().toLowerCase(java.util.Locale.ROOT);
            if (copy.putIfAbsent(key, GraphResource.requireId(source, "shader source")) != null)
                throw new IllegalArgumentException("duplicate shader stage: " + key);
        });
        stages = Map.copyOf(copy);
    }
}
