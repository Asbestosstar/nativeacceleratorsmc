package com.asbestosstar.nativeaccelerator.renderer;

import com.mojang.renderpearl.api.device.BackendCreationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Formats actionable failures from RenderPearl's Vulkan capability selection. */
public final class VulkanBackendDiagnostics {
    private VulkanBackendDiagnostics() {
    }

    /**
     * Report a failed RenderPearl Vulkan operation without changing its fallback semantics.
     * RenderPearl owns the requirement set, so this deliberately reports only the capabilities on the
     * exception rather than maintaining a second Native Accelerator extension list.
     */
    public static void report(String phase, BackendCreationException failure) {
        System.err.println(format(phase, failure));
    }

    /** Build a stable one-line diagnostic suitable for a startup log. */
    public static String format(String phase, BackendCreationException failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        String safePhase = phase == null || phase.isBlank() ? "unknown" : phase.trim();
        String message = failure.getMessage();
        List<String> missing = normalizedMissing(failure.getMissingCapabilities());
        String field = failure.getReason() == BackendCreationException.Reason.VULKAN_MISSING_EXTENSION
                ? "missingExtensions" : "missingCapabilities";
        return "[Native Accelerator] Vulkan " + safePhase + " failed: reason=" + failure.getReason()
                + " message=" + quote(message) + " " + field + "=" + missing;
    }

    private static List<String> normalizedMissing(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) normalized.add(value.trim());
        }
        normalized.sort(Comparator.naturalOrder());
        List<String> unique = new ArrayList<>(normalized.size());
        String previous = null;
        for (String value : normalized) {
            if (!value.equals(previous)) unique.add(value);
            previous = value;
        }
        return List.copyOf(unique);
    }

    private static String quote(String value) {
        if (value == null || value.isBlank()) return "<none>";
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + '"';
    }
}
