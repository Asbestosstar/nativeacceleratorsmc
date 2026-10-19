package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable identity and compilation inputs for one shader source. */
public record ShaderSourceSpec(
        String source,
        String sourceName,
        String version,
        GlslProfile profile,
        String stage,
        String entryPoint,
        Map<String, String> defines,
        List<String> dependencyHashes,
        String compilerIdentity,
        String targetEnvironment) {
    public static final int DEFAULT_MAX_BYTES = 1 << 20;

    /** Backwards-compatible constructor for the original URD triangle contract. */
    public ShaderSourceSpec(String source, String sourceName, String version,
                            GlslProfile profile, String stage) {
        this(source, sourceName, version, profile, stage, "main", Map.of(), List.of(),
                "renderpearl-glsl", profile == GlslProfile.VULKAN ? "vulkan1.0" : "opengl");
    }

    public ShaderSourceSpec {
        Objects.requireNonNull(source, "source");
        sourceName = sourceName == null || sourceName.isBlank() ? "<memory>" : sourceName;
        version = version == null || version.isBlank() ? detectVersion(source) : version.trim();
        Objects.requireNonNull(profile, "profile");
        stage = stage == null || stage.isBlank() ? "unknown" : stage.trim().toLowerCase(java.util.Locale.ROOT);
        entryPoint = entryPoint == null || entryPoint.isBlank() ? "main" : entryPoint.trim();
        defines = immutableDefines(defines);
        dependencyHashes = List.copyOf(dependencyHashes == null ? List.of() : dependencyHashes);
        compilerIdentity = compilerIdentity == null || compilerIdentity.isBlank()
                ? "unknown-compiler" : compilerIdentity.trim();
        targetEnvironment = targetEnvironment == null || targetEnvironment.isBlank()
                ? "unspecified" : targetEnvironment.trim();
        int maxBytes = Integer.getInteger("nativeaccelerator.urd.shader.maxBytes", DEFAULT_MAX_BYTES);
        if (maxBytes <= 0) throw new IllegalArgumentException("shader source byte limit must be positive");
        if (source.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("shader source exceeds configured byte limit");
        }
        for (String hash : dependencyHashes) {
            if (hash == null || hash.isBlank()) throw new IllegalArgumentException("blank dependency hash");
        }
    }

    /** Returns a copy with the complete include/dependency closure attached. */
    public ShaderSourceSpec withDependencies(List<String> hashes) {
        return new ShaderSourceSpec(source, sourceName, version, profile, stage, entryPoint,
                defines, hashes, compilerIdentity, targetEnvironment);
    }

    /** Returns a copy with target/compiler identity changed without changing source bytes. */
    public ShaderSourceSpec forTarget(String compiler, String target) {
        return new ShaderSourceSpec(source, sourceName, version, profile, stage, entryPoint,
                defines, dependencyHashes, compiler, target);
    }

    public String sourceHash() {
        return sha256(source.getBytes(StandardCharsets.UTF_8));
    }

    /** Hashes all inputs that can change the generated backend module. */
    public String compilationIdentity() {
        StringBuilder identity = new StringBuilder()
                .append("source=").append(sourceHash())
                .append("\nname=").append(sourceName)
                .append("\nversion=").append(version)
                .append("\nprofile=").append(profile)
                .append("\nstage=").append(stage)
                .append("\nentry=").append(entryPoint)
                .append("\ncompiler=").append(compilerIdentity)
                .append("\ntarget=").append(targetEnvironment);
        defines.forEach((key, value) -> identity.append("\ndefine=").append(key).append('=').append(value));
        for (String hash : dependencyHashes) identity.append("\ndependency=").append(hash);
        return sha256(identity.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> immutableDefines(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, String> sorted = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("blank preprocessor define");
            sorted.put(key, value == null ? "1" : value);
        });
        return Map.copyOf(new LinkedHashMap<>(sorted));
    }

    private static String detectVersion(String source) {
        for (String line : source.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#version")) return trimmed.substring("#version".length()).trim();
        }
        return "implicit";
    }

    private static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value));
        return out.toString();
    }
}
