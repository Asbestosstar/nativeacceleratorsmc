package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslPreprocessor;
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
            System.out.println("ShaderpackConventionalVariantTest: PASS (wrappers/root includes/active targets)");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
            }
        }
    }
}
