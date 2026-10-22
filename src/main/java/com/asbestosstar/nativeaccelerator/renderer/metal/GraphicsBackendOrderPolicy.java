package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Pure candidate-order policy for the Minecraft renderer selection seam.
 *
 * <p>This class deliberately knows nothing about RenderPearl backend instances, configuration files,
 * Metal probing, or Minecraft state. The integration layer supplies the existing vanilla order and
 * maps the result back to concrete backends.</p>
 */
final class GraphicsBackendOrderPolicy {
    enum Selection {
        DEFAULT,
        OPENGL,
        VULKAN,
        NATIVE_VULKAN,
        METAL
    }

    enum BackendKind {
        METAL,
        OPENGL,
        VULKAN,
        NATIVE_VULKAN
    }

    private GraphicsBackendOrderPolicy() {}

    static List<BackendKind> orderedCandidates(
            Selection choice,
            List<BackendKind> vanillaCandidates,
            boolean metalEligible,
            boolean nativeEligible,
            boolean nativeFirst) {
        Objects.requireNonNull(vanillaCandidates, "vanillaCandidates");
        Selection selected = choice == null ? Selection.DEFAULT : choice;
        List<BackendKind> vanilla = sanitized(vanillaCandidates);

        return switch (selected) {
            case OPENGL -> List.of(BackendKind.OPENGL);
            case METAL -> List.of(BackendKind.METAL, BackendKind.OPENGL);
            case VULKAN -> explicitVulkanOrder(nativeEligible, nativeFirst);
            case NATIVE_VULKAN -> explicitVulkanOrder(nativeEligible, true);
            case DEFAULT -> defaultOrder(vanilla, metalEligible, nativeEligible, nativeFirst);
        };
    }

    private static List<BackendKind> explicitVulkanOrder(boolean nativeEligible, boolean nativeFirst) {
        if (!nativeEligible) return List.of(BackendKind.VULKAN, BackendKind.OPENGL);
        if (nativeFirst) {
            return List.of(BackendKind.NATIVE_VULKAN, BackendKind.VULKAN, BackendKind.OPENGL);
        }
        return List.of(BackendKind.VULKAN, BackendKind.NATIVE_VULKAN, BackendKind.OPENGL);
    }

    private static List<BackendKind> defaultOrder(
            List<BackendKind> vanilla,
            boolean metalEligible,
            boolean nativeEligible,
            boolean nativeFirst) {
        if (nativeFirst && nativeEligible) {
            return List.of(BackendKind.NATIVE_VULKAN, BackendKind.VULKAN, BackendKind.OPENGL);
        }

        List<BackendKind> result = new ArrayList<>();
        for (BackendKind candidate : vanilla) {
            if (candidate == BackendKind.NATIVE_VULKAN) continue;
            result.add(candidate);
            if (nativeEligible && candidate == BackendKind.VULKAN) {
                result.add(BackendKind.NATIVE_VULKAN);
            }
        }
        if (!metalEligible) {
            result.removeIf(candidate -> candidate == BackendKind.METAL);
        }
        return immutable(result);
    }

    private static List<BackendKind> sanitized(List<BackendKind> candidates) {
        EnumSet<BackendKind> seen = EnumSet.noneOf(BackendKind.class);
        List<BackendKind> result = new ArrayList<>(candidates.size());
        for (BackendKind candidate : candidates) {
            if (candidate != null && seen.add(candidate)) result.add(candidate);
        }
        return immutable(result);
    }

    private static List<BackendKind> immutable(List<BackendKind> candidates) {
        return Collections.unmodifiableList(new ArrayList<>(candidates));
    }
}
