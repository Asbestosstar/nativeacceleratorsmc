package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslPreprocessor;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderReflection;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackOutcome;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderSourceSpec;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackConventionalVariantScanner;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ConventionalShaderpackPreparation;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackDimension;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackRenderTargetParser;

import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;

public final class ShaderpackConventionalVariantTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("nativeaccelerator-variant-pack-");
        try {
            Files.createDirectories(root.resolve("shaders/world0"));
            Files.createDirectories(root.resolve("shaders/program"));
            Files.writeString(root.resolve("shaders/shaders.properties"), "");
            Files.writeString(root.resolve("shaders/world0/composite.vsh"), "#version 130\n#define OVERWORLD\n#define VERTEX\n#include \"/program/composite.glsl\"\n");
            Files.writeString(root.resolve("shaders/world0/composite.fsh"), "#version 130\n#define OVERWORLD\n#define FRAGMENT\n#include \"/program/composite.glsl\"\n");
            Files.writeString(root.resolve("shaders/program/composite.glsl"), "#if defined(OVERWORLD) && !defined(NETHER)\n/* RENDERTARGETS: 0,1 */\n#endif\n#if defined(NETHER)\n/* RENDERTARGETS: 7 */\n#endif\n");
            try (var source = com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSourceProvider.open(root)) {
                var variants = new ShaderpackConventionalVariantScanner().scan(source);
                if (variants.size() != 2 || variants.stream().anyMatch(v -> v.dimension() != ShaderpackDimension.OVERWORLD)) {
                    throw new AssertionError(variants);
                }
                var expanded = new GlslPreprocessor(path -> Optional.ofNullable(source.readText("shaders/" + path)))
                        .process("world0/composite.fsh", Map.of("OVERWORLD", "1", "FRAGMENT", "1"));
                if (!expanded.successful() || expanded.source().contains("RENDERTARGETS: 7")) throw new AssertionError(expanded.diagnostics());
                var targets = new ShaderpackRenderTargetParser().parse(expanded.source());
                if (targets.size() != 2 || targets.get(0).attachment() != 0 || targets.get(1).attachment() != 1) throw new AssertionError(targets);
                var prepared = new ConventionalShaderpackPreparation().prepare(source, "probe", com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime.Backend.METAL);
                if (!prepared.variants().isEmpty() || prepared.executable()
                        || prepared.generation().outcome() != com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackOutcome.UNSUPPORTED
                        || prepared.generation().contentHash().isBlank()) throw new AssertionError(prepared);
                if (prepared.generation().diagnostics().stream().noneMatch(value -> value.contains("world-stage"))
                        || prepared.generation().diagnostics().stream().noneMatch(value -> value.contains("MRT"))) {
                    throw new AssertionError("unsupported world/MRT requirements were not reported: " + prepared.generation().diagnostics());
                }
            }
            assertPreparationContract(root);
            System.out.println("ShaderpackConventionalVariantTest: PASS (wrappers/root includes/active targets/fail-closed eligibility)");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
            }
        }
    }

    private static void assertPreparationContract(java.nio.file.Path root) throws Exception {
        var finalRoot = root.resolve("final-only");
        Files.createDirectories(finalRoot.resolve("shaders"));
        Files.writeString(finalRoot.resolve("shaders/shaders.properties"), "");
        Files.writeString(finalRoot.resolve("shaders/final.vsh"), "#version 330\nvoid main(){gl_Position=vec4(0.0); }\n");
        Files.writeString(finalRoot.resolve("shaders/final.fsh"), "#version 330\nout vec4 color; void main(){color=vec4(1.0); }\n");
        var frontend = new StubFrontend();
        var preparation = new ConventionalShaderpackPreparation(frontend,
                new ShaderpackConventionalVariantScanner(), new ShaderpackRenderTargetParser());
        try (var source = com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSourceProvider.open(finalRoot)) {
            var accepted = preparation.prepare(source, "final-only", RendererBackendRuntime.Backend.METAL,
                    ShaderpackDimension.OVERWORLD, java.util.List.of(), Map.of());
            if (accepted.fullscreenPlan() == null || accepted.variants().size() != 2
                    || accepted.generation().outcome() != ShaderpackOutcome.FRONTEND_ONLY
                    || accepted.executable()) throw new AssertionError("single final candidate contract: " + accepted);
            if (frontend.compiled.stream().anyMatch(spec -> !spec.sourceName().endsWith("final.vsh")
                    && !spec.sourceName().endsWith("final.fsh"))) throw new AssertionError(frontend.compiled);
        }
        frontend.compiled.clear();
        Files.writeString(finalRoot.resolve("shaders/composite.vsh"), "#version 330\nvoid main(){}\n");
        Files.writeString(finalRoot.resolve("shaders/composite.fsh"), "#version 330\nout vec4 color; void main(){color=vec4(1.0); }\n");
        try (var source = com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSourceProvider.open(finalRoot)) {
            var rejected = preparation.prepare(source, "extra-pass", RendererBackendRuntime.Backend.METAL,
                    ShaderpackDimension.OVERWORLD, java.util.List.of(), Map.of());
            if (rejected.fullscreenPlan() != null || rejected.generation().outcome() != ShaderpackOutcome.UNSUPPORTED
                    || !frontend.compiled.isEmpty()) throw new AssertionError("extra program must fail before compile: " + rejected);
        }
        Files.delete(finalRoot.resolve("shaders/composite.vsh"));
        Files.delete(finalRoot.resolve("shaders/composite.fsh"));
        try (var source = com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSourceProvider.open(finalRoot)) {
            var unknown = preparation.prepare(source, "unknown-dimension", RendererBackendRuntime.Backend.METAL,
                    ShaderpackDimension.UNKNOWN, java.util.List.of(), Map.of());
            if (unknown.fullscreenPlan() != null || unknown.generation().outcome() != ShaderpackOutcome.UNSUPPORTED
                    || !frontend.compiled.isEmpty()) throw new AssertionError("unknown dimension must fail before compile: " + unknown);
        }
    }

    private static final class StubFrontend implements com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderFrontend {
        private final java.util.List<ShaderSourceSpec> compiled = new java.util.ArrayList<>();

        @Override
        public ShaderModule compile(ShaderSourceSpec source) {
            compiled.add(source);
            return new ShaderModule(source, java.nio.ByteBuffer.allocateDirect(4),
                    new ShaderReflection(java.util.List.of(),
                            source.stage().equals("fragment")
                                    ? java.util.List.of(new ShaderReflection.InterfaceVariable("color", 0, "vec4"))
                                    : java.util.List.of(),
                            java.util.List.of(), 0),
                    java.util.List.of());
        }
    }
}
