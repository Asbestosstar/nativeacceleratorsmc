package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ConventionalShaderpackGraphAdapter;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.FeatureStatus;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackConventionalProfile;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackConventionalScanner;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackFormat;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSource;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSourceProvider;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConventionalShaderpackGraphAdapterTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("nativeaccelerator-graph-adapter-");
        try {
            Files.createDirectories(root.resolve("shaders/world0"));
            Files.writeString(root.resolve("shaders/shaders.properties"), "profile.test=1\n");
            Files.writeString(root.resolve("shaders/world0/gbuffers_terrain.vsh"), "void main(){}\n");
            Files.writeString(root.resolve("shaders/world0/gbuffers_terrain.fsh"),
                    "/* DRAWBUFFERS: 01 */\nvoid main(){}\n");
            try (ShaderPackSource source = ShaderPackSourceProvider.open(root)) {
                ShaderpackConventionalProfile profile = new ShaderpackConventionalScanner().scan(source);
                var result = new ConventionalShaderpackGraphAdapter().build(profile);
                if (!result.validation().valid()) throw new AssertionError(result.validation());
                if (result.supported()) throw new AssertionError("unsupported world/MRT profile accepted");
                if (result.graph().programs().size() != 1 || result.graph().passes().size() != 1) {
                    throw new AssertionError(result.graph());
                }
                for (String feature : List.of("world-stage-passes", "multiple-render-targets")) {
                    if (result.graph().features().get(feature).state() != FeatureStatus.State.UNSUPPORTED) {
                        throw new AssertionError(result.graph().features());
                    }
                }
            }
            ShaderpackConventionalProfile minimal = new ShaderpackConventionalProfile(
                    ShaderpackFormat.OPTIFINE_COMPATIBLE, "shaders", Map.of(), List.of(), Set.of(), List.of());
            if (new ConventionalShaderpackGraphAdapter().build(minimal).supported()) {
                throw new AssertionError("empty conventional profile must not claim support");
            }
            System.out.println("ConventionalShaderpackGraphAdapterTest: PASS");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
