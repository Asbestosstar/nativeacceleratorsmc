package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSource;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSourceProvider;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackOutcome;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ConventionalShaderpackPreparation;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackCatalog;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackFormat;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackPreparation;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackPreparationRoute;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackPreparationRouter;

import java.nio.file.Files;
import java.nio.file.Path;

/** Ensures ordinary packs use conventional preparation without the project fixture manifest. */
public final class ShaderpackPreparationRouterTest {
    public static void main(String[] args) throws Exception {
        Path game = Files.createTempDirectory("nativeaccelerator-preparation-router-");
        try {
            Path conventionalPack = game.resolve("shaderpacks/Conventional");
            Files.createDirectories(conventionalPack.resolve("shaders/world0"));
            Files.writeString(conventionalPack.resolve("shaders/shaders.properties"), "profile.test=1\n");
            Files.writeString(conventionalPack.resolve("shaders/world0/gbuffers_terrain.vsh"), "void main(){}\n");
            Files.writeString(conventionalPack.resolve("shaders/world0/gbuffers_terrain.fsh"),
                    "/* DRAWBUFFERS: 01 */\nvoid main(){}\n");
            if (Files.exists(conventionalPack.resolve("nativeaccelerator.shaderpack.json"))) {
                throw new AssertionError("test fixture must not use Native Accelerator manifest");
            }

            ShaderpackCatalog catalog = new ShaderpackCatalog(game);
            var descriptor = catalog.scan().get(0);
            if (descriptor.conventionalProfile().format() == ShaderpackFormat.UNKNOWN) {
                throw new AssertionError("conventional profile was not discovered");
            }
            if (ShaderpackPreparationRouter.route(descriptor) != ShaderpackPreparationRoute.CONVENTIONAL) {
                throw new AssertionError("manifest-free pack did not select conventional route");
            }
            try (ShaderPackSource source = catalog.openSource(descriptor)) {
                var prepared = new ConventionalShaderpackPreparation().prepare(source, descriptor.id(),
                        RendererBackendRuntime.Backend.METAL);
                if (prepared.executable()) throw new AssertionError("unsupported world/MRT fixture became executable");
                if (prepared.generation().outcome() != ShaderpackOutcome.UNSUPPORTED) {
                    throw new AssertionError("expected unsupported outcome, got " + prepared.generation().outcome());
                }
                String diagnostics = String.join(";", prepared.generation().diagnostics());
                if (!diagnostics.contains("world-stage programs require renderer lifecycle support")
                        || !diagnostics.contains("MRT output requires framebuffer attachment support")) {
                    throw new AssertionError("missing specific conventional feature diagnostics: " + diagnostics);
                }
            }

            Path fixturePack = game.resolve("shaderpacks/Fixture");
            Files.createDirectories(fixturePack.resolve("shaders"));
            Files.writeString(fixturePack.resolve("nativeaccelerator.shaderpack.json"),
                    "{\"format\":1,\"id\":\"fixture\",\"language\":\"glsl\",\"profile\":\"vulkan\","
                            + "\"program\":{\"vertex\":\"shaders/fullscreen.vert\",\"fragment\":\"shaders/fullscreen.frag\"},"
                            + "\"pass\":{\"type\":\"fullscreen-postprocess\",\"output\":\"main\",\"inputs\":[\"main\"]}}\n");
            Files.writeString(fixturePack.resolve("shaders/fullscreen.vert"), "#version 460\nvoid main(){}\n");
            Files.writeString(fixturePack.resolve("shaders/fullscreen.frag"), "#version 460\nvoid main(){}\n");
            var fixture = catalog.scan().stream().filter(value -> value.id().equals("Fixture")).findFirst().orElseThrow();
            if (ShaderpackPreparationRouter.route(fixture) != ShaderpackPreparationRoute.NATIVE_ACCELERATOR_FIXTURE) {
                throw new AssertionError("manifest fixture did not retain its dedicated route");
            }
            System.out.println("ShaderpackPreparationRouterTest: PASS (manifest-free conventional routing, fail-closed features, fixture route)");
        } finally {
            try (var walk = Files.walk(game)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
