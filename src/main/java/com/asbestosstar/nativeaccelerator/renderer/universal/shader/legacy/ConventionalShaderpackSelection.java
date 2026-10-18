package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic, immutable property and dimension resolution for one conventional pack request. */
public record ConventionalShaderpackSelection(
        ShaderPropertiesProfile properties,
        ShaderpackDimension dimension,
        Map<String, String> optionValues,
        List<String> enabledPrograms,
        List<String> diagnostics) {

    public ConventionalShaderpackSelection {
        java.util.Objects.requireNonNull(properties, "properties");
        dimension = dimension == null ? ShaderpackDimension.UNKNOWN : dimension;
        optionValues = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(
                optionValues == null ? Map.of() : optionValues));
        enabledPrograms = List.copyOf(enabledPrograms == null ? List.of() : enabledPrograms);
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    /**
     * Resolves only semantics represented by the bounded properties model. If the selected
     * profile/options leave a property expression unresolved, the diagnostics make the result
     * non-executable rather than silently selecting a different variant.
     */
    public static ConventionalShaderpackSelection resolve(ShaderpackConventionalProfile profile,
                                                           ShaderpackDimension dimension,
                                                           List<String> requestedProfiles,
                                                           Map<String, String> requestedOptions) {
        if (profile == null) throw new IllegalArgumentException("profile is null");
        String propertyText = profile.properties().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
        List<String> profiles = List.copyOf(requestedProfiles == null ? List.of() : requestedProfiles);
        ShaderPropertiesProfile metadata = ShaderPropertiesParser.parse(propertyText, profiles);
        List<String> diagnostics = new ArrayList<>(metadata.diagnostics());
        Map<String, String> options = new LinkedHashMap<>();
        Map<String, String> requested = requestedOptions == null ? Map.of() : requestedOptions;
        for (var entry : metadata.options().entrySet()) {
            String name = entry.getKey();
            ShaderPropertiesProfile.ShaderOption option = entry.getValue();
            String value = requested.getOrDefault(name,
                    option.values().contains(option.defaultValue()) ? option.defaultValue()
                            : option.values().stream().findFirst().orElse(""));
            if (!option.values().contains(value)) {
                diagnostics.add("option." + name + ": unsupported value " + value);
                continue;
            }
            options.put(name, value);
        }
        for (String name : requested.keySet()) {
            if (!metadata.options().containsKey(name)) diagnostics.add("unknown selected option: " + name);
        }
        Map<String, String> booleanOptions = new LinkedHashMap<>();
        for (var entry : options.entrySet()) {
            if (entry.getValue().equalsIgnoreCase("true") || entry.getValue().equalsIgnoreCase("false")) {
                booleanOptions.put(entry.getKey(), entry.getValue());
            }
        }
        ShaderPropertiesProfile parsed = ShaderPropertiesParser.parse(propertyText, profiles, booleanOptions);
        diagnostics = new ArrayList<>(parsed.diagnostics());
        for (var entry : requested.entrySet()) {
            if (!metadata.options().containsKey(entry.getKey())) diagnostics.add("unknown selected option: " + entry.getKey());
            else if (!options.containsKey(entry.getKey())) diagnostics.add("option." + entry.getKey() + ": unsupported value " + entry.getValue());
        }

        boolean hasDimensionPrograms = profile.programs().stream().anyMatch(program ->
                program.name().startsWith("world0/") || program.name().startsWith("world-1/")
                        || program.name().startsWith("world1/"));
        if (dimension == ShaderpackDimension.UNKNOWN && hasDimensionPrograms) {
            diagnostics.add("dimension is unknown; dimension-specific programs cannot be selected");
        }
        List<String> enabled = new ArrayList<>();
        for (ShaderpackProgram program : profile.programs()) {
            if (program.name().startsWith("world0/") || program.name().startsWith("world-1/")
                    || program.name().startsWith("world1/")) {
                if (dimension == ShaderpackDimension.UNKNOWN
                        || !program.name().startsWith("world" + dimensionPath(dimension) + "/")) continue;
            } else if (program.isWorldVariant()) {
                // Non-dimension world passes are not eligible for fullscreen-only selection.
                continue;
            }
            String propertyName = program.familyName();
            if (!parsed.programs().containsKey(propertyName)) {
                enabled.add(program.name());
            } else if (parsed.programEnabled(propertyName, true)) {
                enabled.add(program.name());
            }
        }
        return new ConventionalShaderpackSelection(parsed, dimension, options, enabled, diagnostics);
    }

    private static String dimensionPath(ShaderpackDimension dimension) {
        return switch (dimension) {
            case OVERWORLD -> "0";
            case NETHER -> "-1";
            case END -> "1";
            case UNKNOWN -> "";
        };
    }
}
