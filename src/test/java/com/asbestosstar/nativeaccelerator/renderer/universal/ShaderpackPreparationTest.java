package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackOutcome;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackCatalog;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackPreparation;

import java.nio.file.Files;
import java.nio.file.Path;

/** Verifies the real compiler boundary fails closed when shaderc natives are absent. */
public final class ShaderpackPreparationTest {
    public static void main(String[] args) throws Exception {
        Path game = Files.createTempDirectory("nativeaccelerator-preparation-");
        try {
            Path pack = game.resolve("shaderpacks/Fixture");
            Files.createDirectories(pack.resolve("shaders"));
            Files.writeString(pack.resolve("nativeaccelerator.shaderpack.json"), """
                    {"format":1,"id":"fixture","language":"glsl","profile":"vulkan",
                     "program":{"vertex":"shaders/fullscreen.vert","fragment":"shaders/fullscreen.frag"},
                     "pass":{"type":"fullscreen-postprocess","output":"main","inputs":["main"]}}
                    """);
            Files.writeString(pack.resolve("shaders/fullscreen.vert"), "#version 460\nvoid main(){gl_Position=vec4(0.0);}\n");
            Files.writeString(pack.resolve("shaders/fullscreen.frag"), "#version 460\nlayout(location=0) out vec4 out_color; void main(){out_color=vec4(1.0);}\n");
            ShaderpackCatalog catalog = new ShaderpackCatalog(game);
            var descriptor = catalog.scan().get(0);
            var prepared = new ShaderpackPreparation().prepare(catalog, descriptor,
                    RendererBackendRuntime.Backend.METAL);
            if (prepared.generation() == null || prepared.generation().diagnostics().isEmpty()) {
                throw new AssertionError("preparation did not retain diagnostics");
            }
            if (prepared.executable() && prepared.generation().outcome() != ShaderpackOutcome.EXACT_TRANSLATION) {
                throw new AssertionError("executable preparation has unexpected outcome");
            }
            System.out.println("ShaderpackPreparationTest: " + (prepared.executable() ? "COMPILER_AVAILABLE"
                    : "BLOCKED/DIAGNOSTIC " + prepared.generation().diagnostics()));
        } finally {
            try (var walk = Files.walk(game)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
