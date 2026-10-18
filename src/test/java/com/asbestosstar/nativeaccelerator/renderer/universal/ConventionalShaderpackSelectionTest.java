package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ConventionalShaderpackSelection;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackConventionalProfile;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackDimension;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackFormat;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackProgram;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exercises deterministic profile, program-condition, option and dimension selection. */
public final class ConventionalShaderpackSelectionTest {
    public static void main(String[] args) {
        ShaderpackConventionalProfile profile = new ShaderpackConventionalProfile(
                ShaderpackFormat.OPTIFINE_COMPATIBLE, "shaders",
                Map.of("bloom", "false", "shadows", "true",
                        "profile.high", "bloom=true", "program.composite", "bloom && shadows",
                        "option.quality", "low medium high", "value.quality", "medium"),
                List.of(
                        new ShaderpackProgram("world0/composite", List.of("world0/composite.vsh", "world0/composite.fsh")),
                        new ShaderpackProgram("world-1/composite", List.of("world-1/composite.vsh", "world-1/composite.fsh")),
                        new ShaderpackProgram("world1/final", List.of("world1/final.vsh", "world1/final.fsh"))),
                Set.of(), List.of());

        var selection = ConventionalShaderpackSelection.resolve(profile, ShaderpackDimension.OVERWORLD,
                List.of("high"), Map.of("quality", "high"));
        if (!selection.properties().feature("bloom", false)) throw new AssertionError("profile assignment not resolved");
        if (!selection.properties().programEnabled("composite", false)) throw new AssertionError("program condition not resolved");
        if (!selection.optionValues().get("quality").equals("high")) throw new AssertionError("option override not retained");
        if (!selection.enabledPrograms().equals(List.of("world0/composite"))) {
            throw new AssertionError("wrong dimension/program selection: " + selection.enabledPrograms());
        }
        if (!selection.diagnostics().isEmpty()) throw new AssertionError("unexpected diagnostics: " + selection.diagnostics());

        var invalid = ConventionalShaderpackSelection.resolve(profile, ShaderpackDimension.NETHER,
                List.of("high"), Map.of("quality", "ultra", "unknown", "x"));
        if (invalid.diagnostics().stream().noneMatch(value -> value.contains("unsupported value"))
                || invalid.diagnostics().stream().noneMatch(value -> value.contains("unknown selected option"))) {
            throw new AssertionError("invalid option choices were not surfaced: " + invalid.diagnostics());
        }
        if (!invalid.enabledPrograms().equals(List.of("world-1/composite"))) {
            throw new AssertionError("wrong nether selection: " + invalid.enabledPrograms());
        }
        var unknownProfile = ConventionalShaderpackSelection.resolve(profile, ShaderpackDimension.OVERWORLD,
                List.of("missing"), Map.of("quality", "high"));
        if (unknownProfile.diagnostics().stream().noneMatch(value -> value.contains("unknown requested profile"))) {
            throw new AssertionError("unknown profile did not fail closed: " + unknownProfile.diagnostics());
        }
        var unknownDimension = ConventionalShaderpackSelection.resolve(profile, ShaderpackDimension.UNKNOWN,
                List.of("high"), Map.of("quality", "high"));
        if (unknownDimension.diagnostics().stream().noneMatch(value -> value.contains("dimension is unknown"))
                || unknownDimension.enabledPrograms().stream().anyMatch(name -> name.startsWith("world"))) {
            throw new AssertionError("unknown dimension did not fail closed: " + unknownDimension);
        }
        System.out.println("ConventionalShaderpackSelectionTest: PASS");
    }
}
