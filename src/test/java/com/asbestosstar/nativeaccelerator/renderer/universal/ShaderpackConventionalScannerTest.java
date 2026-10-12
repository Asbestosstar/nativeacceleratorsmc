package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSource;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSourceProvider;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackConventionalScanner;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackFormat;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ConventionalShaderpackGraphAdapter;

import java.nio.file.Files;

public final class ShaderpackConventionalScannerTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("nativeaccelerator-conventional-pack-");
        try {
            Files.createDirectories(root.resolve("shaders"));
            Files.writeString(root.resolve("shaders/shaders.properties"), "profile.test=1\niris.features=none\n");
            Files.writeString(root.resolve("shaders/final.vsh"), "#version 330\nvoid main(){}\n");
            Files.writeString(root.resolve("shaders/final.fsh"), "#version 330\nvoid main(){}\n");
            Files.writeString(root.resolve("shaders/gbuffers_terrain.vsh"), "#version 330\nvoid main(){}\n");
            try (ShaderPackSource source = ShaderPackSourceProvider.open(root)) {
                var profile = new ShaderpackConventionalScanner().scan(source);
                if (profile.format() != ShaderpackFormat.IRIS_COMPATIBLE) throw new AssertionError(profile);
                if (!profile.hasExecutableFullscreenFamily() || !profile.hasWorldPrograms()) throw new AssertionError(profile);
                if (!profile.unsupportedFeatures().contains("world-stage-passes")) throw new AssertionError(profile);
                if (profile.programs().size() != 2) throw new AssertionError(profile.programs());
                var graph = new ConventionalShaderpackGraphAdapter().build(profile);
                if (!graph.validation().valid()) throw new AssertionError(graph.validation());
                if (graph.supported()) throw new AssertionError("world graph unexpectedly supported");
                if (graph.graph().programs().size() != 2 || graph.graph().passes().size() != 2) {
                    throw new AssertionError(graph.graph());
                }
                if (graph.graph().features().get("world-stage-passes").state()
                        != com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.FeatureStatus.State.UNSUPPORTED) {
                    throw new AssertionError(graph.graph().features());
                }
            }
            System.out.println("ShaderpackConventionalScannerTest: PASS (root/properties/program families)");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
            }
        }
    }
}
