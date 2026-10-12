package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Canonical names and semantic types for the common GLSL/Minecraft shaderpack uniforms. */
public final class StandardUniformRegistry {
    private static final Map<String, Uniform> UNIFORMS;

    static {
        Map<String, Uniform> values = new LinkedHashMap<>();
        add(values, "ModelViewMat", "mat4");
        add(values, "ProjMat", "mat4");
        add(values, "TextureMat", "mat4");
        add(values, "ScreenSize", "vec2");
        add(values, "ColorModulator", "vec4");
        add(values, "Light0_Direction", "vec3");
        add(values, "Light1_Direction", "vec3");
        add(values, "GlintAlpha", "float");
        add(values, "FogStart", "float");
        add(values, "FogEnd", "float");
        add(values, "FogColor", "vec4");
        add(values, "FogShape", "int");
        add(values, "GameTime", "float");
        add(values, "ChunkOffset", "vec3");
        add(values, "ModelOffset", "vec3");
        add(values, "LineWidth", "float");
        add(values, "Color", "vec4");
        add(values, "NormalMat", "mat3");
        add(values, "IViewRotMat", "mat3");
        add(values, "InvViewMat", "mat4");
        add(values, "InvProjMat", "mat4");
        add(values, "CameraPosition", "vec3");
        add(values, "WorldTime", "int");
        add(values, "PlayerMood", "float");
        add(values, "RenderDistance", "int");
        add(values, "ViewWidth", "int");
        add(values, "ViewHeight", "int");
        add(values, "Near", "float");
        add(values, "Far", "float");
        add(values, "AspectRatio", "float");
        add(values, "FrameTime", "float");
        add(values, "FrameCounter", "int");
        add(values, "World", "int");
        add(values, "RainStrength", "float");
        add(values, "Wetness", "float");
        add(values, "SunAngle", "float");
        add(values, "ShadowAngle", "float");
        add(values, "MoonPhase", "int");
        add(values, "IsEyeInWater", "int");
        add(values, "NightVision", "float");
        add(values, "Blindness", "float");
        add(values, "DarknessFactor", "float");
        add(values, "EyeAltitude", "float");
        add(values, "EyeBrightness", "ivec2");
        add(values, "Fog", "vec4");
        UNIFORMS = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private StandardUniformRegistry() { }

    public static Optional<Uniform> find(String name) {
        return Optional.ofNullable(UNIFORMS.get(Objects.requireNonNull(name, "name")));
    }

    public static List<Uniform> all() { return List.copyOf(UNIFORMS.values()); }
    public static Map<String, Uniform> byName() { return UNIFORMS; }
    public static boolean isStandard(String name) { return name != null && UNIFORMS.containsKey(name); }

    private static void add(Map<String, Uniform> values, String name, String type) {
        values.put(name, new Uniform(name, type));
    }

    public record Uniform(String name, String type) {
        public Uniform {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }
}
