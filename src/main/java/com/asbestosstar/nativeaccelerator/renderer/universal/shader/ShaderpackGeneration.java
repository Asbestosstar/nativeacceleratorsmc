package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable, fully prepared shaderpack generation that can be published atomically. */
public record ShaderpackGeneration(
        String packId,
        String contentHash,
        ShaderpackOutcome outcome,
        List<String> diagnostics,
        Instant preparedAt) {
    public ShaderpackGeneration {
        packId = requireText(packId, "packId");
        contentHash = requireText(contentHash, "contentHash");
        Objects.requireNonNull(outcome, "outcome");
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        preparedAt = preparedAt == null ? Instant.now() : preparedAt;
    }

    public boolean executable() {
        return outcome.acceptedByDefault();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value;
    }
}
