package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Loader-neutral identity for one compiled shader variant.
 *
 * <p>The seven dimensions are deliberately kept separate. A pack/dependency change, compiler
 * option change, backend translation change, device change, attachment change, or render-state
 * change must not accidentally reuse a compiled artifact from another identity. The canonical
 * representation uses UTF-8 byte lengths, so values cannot become ambiguous when they contain
 * separators.</p>
 */
public final class ShaderCompilationKey {
    private static final String FORMAT = "shader-compilation-key-v1";

    private final String packIdentity;
    private final String dependencyIdentity;
    private final String compilerIdentity;
    private final String backendIdentity;
    private final String deviceIdentity;
    private final String attachmentIdentity;
    private final String renderStateIdentity;
    private final int hashCode;

    public ShaderCompilationKey(String packIdentity,
            String dependencyIdentity,
            String compilerIdentity,
            String backendIdentity,
            String deviceIdentity,
            String attachmentIdentity,
            String renderStateIdentity) {
        this.packIdentity = required("packIdentity", packIdentity);
        this.dependencyIdentity = required("dependencyIdentity", dependencyIdentity);
        this.compilerIdentity = required("compilerIdentity", compilerIdentity);
        this.backendIdentity = required("backendIdentity", backendIdentity);
        this.deviceIdentity = required("deviceIdentity", deviceIdentity);
        this.attachmentIdentity = required("attachmentIdentity", attachmentIdentity);
        this.renderStateIdentity = required("renderStateIdentity", renderStateIdentity);
        this.hashCode = Objects.hash(this.packIdentity, this.dependencyIdentity, this.compilerIdentity,
                this.backendIdentity, this.deviceIdentity, this.attachmentIdentity, this.renderStateIdentity);
    }

    public String packIdentity() { return packIdentity; }
    public String dependencyIdentity() { return dependencyIdentity; }
    public String compilerIdentity() { return compilerIdentity; }
    public String backendIdentity() { return backendIdentity; }
    public String deviceIdentity() { return deviceIdentity; }
    public String attachmentIdentity() { return attachmentIdentity; }
    public String renderStateIdentity() { return renderStateIdentity; }

    /** A stable, unambiguous representation suitable for diagnostics or a cache key input. */
    public String canonicalForm() {
        StringBuilder out = new StringBuilder(FORMAT.length() + 256).append(FORMAT);
        append(out, packIdentity);
        append(out, dependencyIdentity);
        append(out, compilerIdentity);
        append(out, backendIdentity);
        append(out, deviceIdentity);
        append(out, attachmentIdentity);
        append(out, renderStateIdentity);
        return out.toString();
    }

    /** SHA-256 of {@link #canonicalForm()}, encoded as lowercase hexadecimal. */
    public String digest() {
        return sha256(canonicalForm());
    }

    /** Alias for callers that use cache terminology. */
    public String cacheNamespace() { return digest(); }

    private static void append(StringBuilder out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.append('|').append(bytes.length).append(':').append(value);
    }

    private static String required(String name, String value) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ShaderCompilationKey that)) return false;
        return packIdentity.equals(that.packIdentity)
                && dependencyIdentity.equals(that.dependencyIdentity)
                && compilerIdentity.equals(that.compilerIdentity)
                && backendIdentity.equals(that.backendIdentity)
                && deviceIdentity.equals(that.deviceIdentity)
                && attachmentIdentity.equals(that.attachmentIdentity)
                && renderStateIdentity.equals(that.renderStateIdentity);
    }

    @Override public int hashCode() { return hashCode; }

    @Override public String toString() { return "ShaderCompilationKey[" + digest() + "]"; }
}
