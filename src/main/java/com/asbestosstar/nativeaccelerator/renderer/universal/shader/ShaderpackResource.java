package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.Objects;

/** Immutable declaration of one named shaderpack interface resource. */
public record ShaderpackResource(
        String name,
        ShaderpackResourceKind kind,
        String type,
        int arrayLength,
        int requestedBinding,
        boolean optional,
        boolean readOnly) {
    /** Allocate a binding deterministically rather than requesting a fixed slot. */
    public static final int AUTO_BINDING = -1;

    public ShaderpackResource {
        name = required("name", name);
        Objects.requireNonNull(kind, "kind");
        type = required("type", type);
        if (arrayLength < 1) throw new IllegalArgumentException("arrayLength must be positive");
        if (requestedBinding < AUTO_BINDING) throw new IllegalArgumentException("binding must be non-negative or AUTO_BINDING");
    }

    public ShaderpackResource(String name, ShaderpackResourceKind kind, String type) {
        this(name, kind, type, 1, AUTO_BINDING, false,
                kind == ShaderpackResourceKind.SAMPLER || kind == ShaderpackResourceKind.VERTEX_ATTRIBUTE);
    }

    private static String required(String label, String value) {
        Objects.requireNonNull(value, label);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        return normalized;
    }
}
