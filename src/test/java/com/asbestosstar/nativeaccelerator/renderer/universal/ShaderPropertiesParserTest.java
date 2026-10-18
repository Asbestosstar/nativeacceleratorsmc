package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderPropertiesParser;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderPropertiesProfile;

import java.util.List;
import java.util.Set;

public final class ShaderPropertiesParserTest {
    public static void main(String[] args) {
        String source = """
                # feature flags
                shadows=true
                bloom=false
                profile.base=shadowMapResolution=2048 shadows=false
                profile.high=base bloom=true
                profile.low=shadowMapResolution=512
                program.composite=shadows && !bloom
                program.final=shadows == true || bloom
                screen=<profile> [SHADOWS] <empty>
                screen.SHADOWS=shadowMapResolution [BLOOM]
                sliders=shadowMapResolution
                option.quality=low medium high
                value.quality=medium
                name.quality=Render quality
                """;
        ShaderPropertiesProfile profile = ShaderPropertiesParser.parse(source, Set.of("high"));
        require(!profile.feature("shadows", true), "selected profile override");
        require(profile.feature("bloom", true), "profile feature assignment");
        require(profile.feature("shadowMapResolution", false) == false, "numeric profile values are excluded from boolean feature map");
        require(profile.programEnabled("composite", true) == false, "program expression false result");
        require(profile.programEnabled("final", false), "program expression true result");
        require(profile.screens().get("SHADOWS").equals(java.util.List.of("shadowMapResolution", "[BLOOM]")), "screen metadata");
        require(profile.sliders().contains("shadowMapResolution"), "slider metadata");
        require(profile.options().get("quality").values().equals(java.util.List.of("low", "medium", "high")), "option values");
        require(profile.options().get("quality").defaultValue().equals("medium"), "option default");
        require(profile.options().get("quality").displayName().equals("Render quality"), "option display name");
        require(ShaderPropertiesParser.parse("profile.a=bloom=true\nprofile.b=a shadows=true", Set.of("b"))
                .feature("bloom", false), "parent inheritance");
        var firstOrder = ShaderPropertiesParser.parse(
                "profile.a=bloom=true\nprofile.b=bloom=false", List.of("a", "b"));
        var reverseOrder = ShaderPropertiesParser.parse(
                "profile.a=bloom=true\nprofile.b=bloom=false", List.of("b", "a"));
        require(firstOrder.feature("bloom", false) && !reverseOrder.feature("bloom", true),
                "conflicting profile assignments respect caller order");
        require(ShaderPropertiesParser.parse("profile.a=bloom=true", List.of("missing")).diagnostics()
                .stream().anyMatch(value -> value.contains("unknown requested profile")),
                "unknown requested profile diagnostic");
        StringBuilder manyAssignments = new StringBuilder();
        for (int i = 0; i < 500; i++) manyAssignments.append("profile.p").append(i).append("=feature").append(i).append("=true\n");
        require(ShaderPropertiesParser.parse(manyAssignments.toString(), List.of()).diagnostics().isEmpty(),
                "realistic profile assignment volume stays within the bounded limit");
        require(ShaderPropertiesParser.parse("option.enabled=true false\nprogram.composite.enabled=enabled",
                List.of(), java.util.Map.of("enabled", "true")).programEnabled("composite", false),
                "selected boolean option feeds conventional .enabled program expression");
        require(!ShaderPropertiesParser.parse("profile.a=b\nprofile.b=a", Set.of("a")).diagnostics().isEmpty(), "cycle diagnostic");
        boolean bounded = false;
        try { ShaderPropertiesParser.parse("x=true", Set.of(), new ShaderPropertiesParser.Limits(2, 10, 2, 2, 10, 2)); }
        catch (IllegalArgumentException expected) { bounded = true; }
        require(bounded, "input bound");
        boolean invalidExpression = false;
        try { ShaderPropertiesParser.parse("x=true\nprogram.a=x &&", Set.of()); }
        catch (RuntimeException error) { invalidExpression = false; }
        // Invalid expressions are excluded with diagnostics, not allowed to abort the metadata parse.
        ShaderPropertiesProfile invalid = ShaderPropertiesParser.parse("x=true\nprogram.a=x &&", Set.of());
        require(!invalid.programs().containsKey("a") && !invalid.diagnostics().isEmpty(), "invalid expression diagnostic");
        System.out.println("ShaderPropertiesParserTest: PASS");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
