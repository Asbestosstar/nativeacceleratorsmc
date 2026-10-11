package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Read-only, deterministic shader-pack source view. */
public interface ShaderPackSource extends AutoCloseable {
    /** Returns a normalized relative path, or an empty result for an absent entry. */
    java.util.Optional<byte[]> read(String path);

    /** Returns normalized file names in lexicographic order. */
    List<String> list();

    /** SHA-256 of names and bytes in deterministic listing order. */
    default String contentHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String path : list()) {
                digest.update(path.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                read(path).ifPresent(bytes -> digest.update(bytes));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    default String readText(String path) {
        return read(path).map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse(null);
    }

    List<ShaderPackDiagnostic> diagnostics();

    @Override default void close() throws IOException { }
}
