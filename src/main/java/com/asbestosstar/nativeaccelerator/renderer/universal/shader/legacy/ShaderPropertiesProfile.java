package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable typed result of parsing and resolving a legacy shaders.properties file. */
public record ShaderPropertiesProfile(
        Map<String, Boolean> features,
        Map<String, Boolean> programs,
        Map<String, ShaderOption> options,
        Map<String, List<String>> screens,
        Set<String> sliders,
        List<String> selectedProfiles,
        List<String> diagnostics) {
    public ShaderPropertiesProfile {
        features = Map.copyOf(features);
        programs = Map.copyOf(programs);
        options = Map.copyOf(options);
        screens = screens.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        sliders = Set.copyOf(sliders);
        selectedProfiles = List.copyOf(selectedProfiles);
        diagnostics = List.copyOf(diagnostics);
    }

    public record ShaderOption(List<String> values, String defaultValue, String displayName) {
        public ShaderOption {
            values = List.copyOf(values);
        }
    }

    public boolean feature(String name, boolean fallback) {
        return features.getOrDefault(name, fallback);
    }

    public boolean programEnabled(String name, boolean fallback) {
        return programs.getOrDefault(name, fallback);
    }
}
