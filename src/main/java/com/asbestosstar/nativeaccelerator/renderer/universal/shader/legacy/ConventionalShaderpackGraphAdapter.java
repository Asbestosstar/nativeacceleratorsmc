package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts conventional source inventory into a conservative, validated pass graph. */
public final class ConventionalShaderpackGraphAdapter {
    private static final String SCENE_COLOR = "conventional.scene-color";

    public Result build(ShaderpackConventionalProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile is null");
        if (profile.programs().size() + 1 > ShaderProgramGraph.DEFAULT_LIMITS.maxResources()
                || profile.programs().size() > ShaderProgramGraph.DEFAULT_LIMITS.maxPrograms()
                || profile.programs().size() > ShaderProgramGraph.DEFAULT_LIMITS.maxPasses()) {
            String error = "conventional graph exceeds configured resource/program/pass limits";
            ShaderProgramGraph empty = new ShaderProgramGraph(Map.of(), Map.of(), List.of(), Map.of());
            return new Result(empty, new ShaderProgramGraph.Validation(List.of(error), List.of(), List.of()),
                    List.of(error), false);
        }
        ShaderProgramGraph.Builder builder = new ShaderProgramGraph.Builder();
        List<String> diagnostics = new ArrayList<>(profile.diagnostics());
        Map<String, String> programIds = new LinkedHashMap<>();
        builder.resource(new GraphResource(SCENE_COLOR, GraphResource.Kind.TEXTURE,
                "backend-scene-color", 0, 0, 0, true, true));

        for (ShaderpackProgram sourceProgram : profile.programs()) {
            String id = safeId("program", sourceProgram.name());
            programIds.put(sourceProgram.name(), id);
            Map<String, String> stages = new LinkedHashMap<>();
            for (String stagePath : sourceProgram.stages()) {
                String lower = stagePath.toLowerCase(java.util.Locale.ROOT);
                String stage = lower.endsWith(".vsh") ? "vertex"
                        : lower.endsWith(".fsh") ? "fragment"
                        : lower.endsWith(".gsh") ? "geometry"
                        : lower.endsWith(".tes") ? "tess_evaluation"
                        : lower.endsWith(".tcs") ? "tess_control"
                        : lower.endsWith(".csh") ? "compute" : "source";
                if (stages.putIfAbsent(stage, safeId("source", stagePath)) != null) {
                    diagnostics.add("duplicate " + stage + " stage for " + sourceProgram.name());
                }
            }
            builder.program(new ShaderProgram(id, stages));
            String output = safeId("resource", "output/" + sourceProgram.name());
            builder.resource(new GraphResource(output, GraphResource.Kind.TEXTURE));
        }

        String prior = null;
        for (ShaderpackProgram sourceProgram : profile.programs()) {
            String id = programIds.get(sourceProgram.name());
            String output = safeId("resource", "output/" + sourceProgram.name());
            List<ShaderPass.ResourceUse> uses = List.of(
                    new ShaderPass.ResourceUse(SCENE_COLOR, ShaderPass.Access.READ),
                    new ShaderPass.ResourceUse(output, ShaderPass.Access.WRITE));
            Set<String> after = prior == null ? Set.of() : Set.of(prior);
            builder.pass(new ShaderPass(safeId("pass", sourceProgram.name()), id, uses, after, true));
            prior = safeId("pass", sourceProgram.name());
        }

        for (String feature : profile.unsupportedFeatures()) {
            builder.feature(new FeatureStatus(feature, FeatureStatus.State.UNSUPPORTED,
                    "Conventional feature has no validated renderer implementation"));
        }
        addFeature(builder, profile, "world-stage-passes", profile.hasWorldPrograms());
        addFeature(builder, profile, "multiple-render-targets", profile.unsupportedFeatures().contains("multiple-render-targets"));
        addFeature(builder, profile, "shadow-passes", profile.programs().stream().anyMatch(p -> p.familyName().startsWith("shadow")));
        addFeature(builder, profile, "deferred-passes", profile.programs().stream().anyMatch(p -> p.familyName().startsWith("deferred")));

        ShaderProgramGraph graph = builder.build();
        ShaderProgramGraph.Validation validation = graph.validate();
        diagnostics.addAll(validation.errors().stream().map(error -> "graph validation: " + error).toList());
        return new Result(graph, validation, diagnostics, profile.format() != ShaderpackFormat.UNKNOWN
                && !profile.programs().isEmpty() && !graph.passes().isEmpty()
                && profile.unsupportedFeatures().isEmpty() && validation.valid());
    }

    private static void addFeature(ShaderProgramGraph.Builder builder, ShaderpackConventionalProfile profile,
                                   String feature, boolean present) {
        if (present && !profile.unsupportedFeatures().contains(feature)) {
            builder.feature(new FeatureStatus(feature, FeatureStatus.State.UNSUPPORTED,
                    "Detected by conventional scan; pass lifecycle is not implemented"));
        }
    }

    private static String safeId(String prefix, String value) {
        StringBuilder result = new StringBuilder(prefix).append('.');
        for (int i = 0; i < value.length() && result.length() < 250; i++) {
            char c = value.charAt(i);
            result.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        return result.toString();
    }

    public record Result(ShaderProgramGraph graph, ShaderProgramGraph.Validation validation,
                         List<String> diagnostics, boolean supported) {
        public Result {
            java.util.Objects.requireNonNull(graph, "graph");
            java.util.Objects.requireNonNull(validation, "validation");
            diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
            supported &= validation.valid();
        }
    }
}
