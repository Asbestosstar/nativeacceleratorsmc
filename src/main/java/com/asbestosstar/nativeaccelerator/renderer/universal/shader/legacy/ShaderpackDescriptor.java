package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.nio.file.Path;
import java.util.Set;

/** Immutable, Minecraft-free description of one discovered external shaderpack. */
public record ShaderpackDescriptor(
        String id,
        String displayName,
        Path path,
        Kind kind,
        Set<ShaderTechnology> technologies,
        ShaderpackStatus status,
        String reason,
        String contentHash,
        ShaderpackConventionalProfile conventionalProfile) {

    public enum Kind { DIRECTORY, ZIP, JAR, OTHER }

    public ShaderpackDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is blank");
        if (displayName == null || displayName.isBlank()) displayName = id;
        if (path == null) throw new IllegalArgumentException("path is null");
        technologies = technologies == null ? Set.of() : Set.copyOf(technologies);
        status = status == null ? ShaderpackStatus.UNKNOWN : status;
        reason = reason == null ? "" : reason;
        contentHash = contentHash == null ? "" : contentHash;
        conventionalProfile = conventionalProfile == null
                ? new ShaderpackConventionalProfile(ShaderpackFormat.UNKNOWN, "", java.util.Map.of(), java.util.List.of(), java.util.Set.of(), java.util.List.of())
                : conventionalProfile;
    }

    public String technologyLabel() {
        if (technologies.isEmpty()) return ShaderTechnology.UNKNOWN.label();
        String label = technologies.stream().map(ShaderTechnology::label).sorted()
                .reduce((a, b) -> a + ", " + b).orElse("Unknown");
        return conventionalProfile.format() == ShaderpackFormat.UNKNOWN
                ? label : label + " / " + conventionalProfile.format().name();
    }
}
