package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderPropertiesParser;

import java.util.Set;

/** Plain model checks for the declared shader options surfaced by the settings screen. */
public final class ShaderpackSettingsModelTest {
    public static void main(String[] args) {
        var parsed = ShaderPropertiesParser.parse("""
                option.SHADOW_QUALITY=low medium high
                value.SHADOW_QUALITY=medium
                name.SHADOW_QUALITY=Shadow Quality
                sliders=SHADOW_QUALITY
                """, Set.of());
        var option = parsed.options().get("SHADOW_QUALITY");
        if (option == null || !option.values().equals(java.util.List.of("low", "medium", "high"))) {
            throw new AssertionError("declared choices missing: " + parsed);
        }
        if (!"medium".equals(option.defaultValue()) || !"Shadow Quality".equals(option.displayName())) {
            throw new AssertionError("default/display metadata lost: " + option);
        }
        if (!parsed.sliders().contains("SHADOW_QUALITY")) throw new AssertionError("slider metadata missing");
        if (!ShaderPropertiesParser.parse("screen=profile.*\n", Set.of()).options().isEmpty()) {
            throw new AssertionError("unknown screen declarations became editable options");
        }
        System.out.println("ShaderpackSettingsModelTest: PASS");
    }
}
