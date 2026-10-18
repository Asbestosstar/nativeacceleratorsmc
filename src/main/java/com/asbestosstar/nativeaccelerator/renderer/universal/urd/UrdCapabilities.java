package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import java.util.Set;

/** Backend-neutral capability snapshot. Backend-specific handles must not escape this type. */
public record UrdCapabilities(
        String backend,
        Set<String> features,
        int maxColorAttachments,
        boolean validationAvailable) {
    public UrdCapabilities {
        backend = backend == null || backend.isBlank() ? "unknown" : backend;
        features = Set.copyOf(features == null ? Set.of() : features);
        if (maxColorAttachments < 0) throw new IllegalArgumentException("negative color attachment limit");
    }

    public boolean supports(String feature) {
        return feature != null && features.contains(feature);
    }
}
