package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static, loader-neutral result of scanning a conventional shaderpack layout. */
public record ShaderpackConventionalProfile(
        ShaderpackFormat format,
        String root,
        Map<String, String> properties,
        List<ShaderpackProgram> programs,
        Set<String> unsupportedFeatures,
        List<String> diagnostics) {
    public ShaderpackConventionalProfile {
        format = format == null ? ShaderpackFormat.UNKNOWN : format;
        root = root == null ? "" : root;
        properties = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(
                properties == null ? Map.of() : properties));
        programs = List.copyOf(programs == null ? List.of() : programs);
        unsupportedFeatures = Set.copyOf(unsupportedFeatures == null ? Set.of() : unsupportedFeatures);
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    public boolean hasExecutableFullscreenFamily() {
        return programs.stream().anyMatch(program ->
                (program.name().startsWith("final") || program.name().startsWith("composite"))
                        && program.hasStage(".vsh") && program.hasStage(".fsh"));
    }

    public boolean hasWorldPrograms() {
        return programs.stream().anyMatch(ShaderpackProgram::isWorldVariant);
    }
}
