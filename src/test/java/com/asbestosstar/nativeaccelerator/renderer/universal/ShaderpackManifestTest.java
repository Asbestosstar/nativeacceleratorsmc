package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackManifest;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackManifestParser;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSourceProvider;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ShaderpackManifestTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("nativeaccelerator-pack-");
        try {
            Files.writeString(root.resolve("nativeaccelerator.shaderpack.json"), """
                    {"format":1,"id":"fixture","name":"Fixture","language":"glsl","profile":"vulkan",
                     "program":{"vertex":"shaders/fullscreen.vert","fragment":"shaders/fullscreen.frag","defines":{"TEST":"1"}},
                     "pass":{"type":"fullscreen-postprocess","output":"main","inputs":["main"]}}
                    """);
            Files.createDirectories(root.resolve("shaders"));
            Files.writeString(root.resolve("shaders/fullscreen.vert"), "#version 460\nvoid main() { gl_Position = vec4(0.0); }\n");
            Files.writeString(root.resolve("shaders/fullscreen.frag"), "#version 460\nlayout(location=0) out vec4 color; void main() { color=vec4(1.0); }\n");
            try (var source = ShaderPackSourceProvider.open(root)) {
                ShaderpackManifest manifest = new ShaderpackManifestParser().parse(source);
                if (!"fixture".equals(manifest.id()) || !"shaders/fullscreen.vert".equals(manifest.program().vertex())
                        || !manifest.pass().inputs().equals(java.util.List.of("main"))) {
                    throw new AssertionError("valid manifest was not parsed: " + manifest);
                }
            }
            Files.writeString(root.resolve("nativeaccelerator.shaderpack.json"),
                    "{\"format\":1,\"id\":\"bad\",\"language\":\"glsl\",\"profile\":\"vulkan\","
                            + "\"program\":{\"vertex\":\"../escape.vert\",\"fragment\":\"f.frag\"},"
                            + "\"pass\":{\"type\":\"fullscreen-postprocess\",\"output\":\"main\"}}");
            try (var source = ShaderPackSourceProvider.open(root)) {
                boolean rejected = false;
                try { new ShaderpackManifestParser().parse(source); } catch (IllegalArgumentException expected) { rejected = true; }
                if (!rejected) throw new AssertionError("unsafe shader path was accepted");
            }
            System.out.println("ShaderpackManifestTest: PASS");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
