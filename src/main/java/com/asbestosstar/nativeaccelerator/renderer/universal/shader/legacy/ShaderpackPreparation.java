package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslFrontend;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslProfile;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderDiagnostic;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSource;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderSourceSpec;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackGeneration;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prepares the first executable shaderpack profile without creating GPU resources.
 *
 * <p>This is intentionally a pure preparation boundary. Render-thread publication and GPU pipeline
 * ownership remain separate, so a blocked compiler or invalid pack cannot disturb vanilla rendering.</p>
 */
public final class ShaderpackPreparation {
    private final GlslFrontend frontend;
    private final ShaderpackManifestParser parser;

    public ShaderpackPreparation() {
        this(new GlslFrontend(), new ShaderpackManifestParser());
    }

    public ShaderpackPreparation(GlslFrontend frontend, ShaderpackManifestParser parser) {
        this.frontend = java.util.Objects.requireNonNull(frontend, "frontend");
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
    }

    public Prepared prepare(ShaderpackCatalog catalog, ShaderpackDescriptor descriptor,
                           RendererBackendRuntime.Backend backend) {
        if (catalog == null || descriptor == null) {
            return blocked("shaderpack catalog/descriptor is missing");
        }
        if (backend == null || backend == RendererBackendRuntime.Backend.NONE
                || backend == RendererBackendRuntime.Backend.OTHER) {
            return blocked("shaderpack backend is unavailable");
        }
        try (ShaderPackSource source = catalog.openSource(descriptor)) {
            ShaderpackManifest manifest = parser.parse(source);
            GlslProfile profile = profile(manifest.profile());
            ShaderpackManifest.Program program = manifest.program();
            ShaderSourceSpec vertexSpec = sourceSpec(source, program.vertex(), "vertex", profile, program.defines());
            ShaderSourceSpec fragmentSpec = sourceSpec(source, program.fragment(), "fragment", profile, program.defines());
            ShaderModule vertex = frontend.compile(vertexSpec, path -> java.util.Optional.ofNullable(source.readText(path)));
            ShaderModule fragment = frontend.compile(fragmentSpec, path -> java.util.Optional.ofNullable(source.readText(path)));
            List<String> diagnostics = new ArrayList<>();
            diagnostics.add("backend=" + backend.name().toLowerCase(Locale.ROOT));
            diagnostics.add("pack=" + manifest.id());
            diagnostics.add("contentHash=" + source.contentHash());
            diagnostics.addAll(shaderDiagnostics(vertex, "vertex"));
            diagnostics.addAll(shaderDiagnostics(fragment, "fragment"));
            if (!vertex.isValid() || !fragment.isValid()) {
                return new Prepared(manifest, vertex, fragment,
                        generation(manifest, source.contentHash(), blockedOutcome(vertex, fragment), diagnostics));
            }
            validateFullscreenPair(vertex, fragment);
            diagnostics.add("shader modules compiled");
            return new Prepared(manifest, vertex, fragment,
                    generation(manifest, source.contentHash(), ShaderpackOutcome.EXACT_TRANSLATION, diagnostics));
        } catch (Throwable failure) {
            String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new Prepared(null, null, null, new ShaderpackGeneration(
                    descriptor.id(), descriptor.contentHash().isBlank() ? "unknown" : descriptor.contentHash(),
                    ShaderpackOutcome.BLOCKED, List.of("BLOCKED: " + detail), java.time.Instant.now()));
        }
    }

    private static ShaderSourceSpec sourceSpec(ShaderPackSource source, String path, String stage,
                                                GlslProfile profile, java.util.Map<String, String> defines) {
        String text = source.readText(path);
        if (text == null) throw new IllegalArgumentException("missing " + stage + " shader: " + path);
        return new ShaderSourceSpec(text, path, null, profile, stage, "main", defines, List.of(),
                "renderpearl-glsl", profile == GlslProfile.VULKAN ? "vulkan1.2" : "opengl");
    }

    private static GlslProfile profile(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "vulkan", "vulkan1.2", "spirv" -> GlslProfile.VULKAN;
            case "opengl", "glsl" -> GlslProfile.OPENGL;
            default -> throw new IllegalArgumentException("unsupported GLSL profile: " + value);
        };
    }

    private static void validateFullscreenPair(ShaderModule vertex, ShaderModule fragment) {
        if (!vertex.reflection().descriptors().isEmpty() || !fragment.reflection().descriptors().isEmpty()) {
            throw new IllegalArgumentException("fullscreen fixture does not support descriptors");
        }
        if (vertex.reflection().pushConstantBytes() != 0 || fragment.reflection().pushConstantBytes() != 0) {
            throw new IllegalArgumentException("fullscreen fixture does not support push constants");
        }
        var vertexOutput = vertex.reflection().outputs().stream().filter(value -> value.location() == 0).findFirst().orElse(null);
        var fragmentInput = fragment.reflection().inputs().stream().filter(value -> value.location() == 0).findFirst().orElse(null);
        if (vertexOutput != null && fragmentInput != null && !vertexOutput.type().equals(fragmentInput.type())) {
            throw new IllegalArgumentException("fullscreen interface type mismatch");
        }
        if (fragment.reflection().outputs().stream().noneMatch(value -> value.location() == 0)) {
            throw new IllegalArgumentException("fragment shader has no color output at location 0");
        }
    }

    private static ShaderpackOutcome blockedOutcome(ShaderModule vertex, ShaderModule fragment) {
        boolean compilerBlocked = java.util.stream.Stream.of(vertex, fragment)
                .filter(java.util.Objects::nonNull)
                .flatMap(module -> module.diagnostics().stream())
                .anyMatch(diagnostic -> diagnostic.message().contains("Failed to load a library")
                        || diagnostic.message().contains("UnsatisfiedLinkError")
                        || diagnostic.message().contains("Could not load")
                        || diagnostic.message().contains("NoSuchMethodError"));
        return compilerBlocked ? ShaderpackOutcome.BLOCKED : ShaderpackOutcome.UNSUPPORTED;
    }

    private static List<String> shaderDiagnostics(ShaderModule module, String stage) {
        return module.diagnostics().stream().map(value -> stage + ": " + format(value)).toList();
    }

    private static String format(ShaderDiagnostic diagnostic) {
        return diagnostic.severity() + " " + diagnostic.sourceName() + ":" + diagnostic.line() + ":" + diagnostic.column()
                + " " + diagnostic.message();
    }

    private static ShaderpackGeneration generation(ShaderpackManifest manifest, String hash,
                                                     ShaderpackOutcome outcome, List<String> diagnostics) {
        return new ShaderpackGeneration(manifest.id(), hash, outcome, diagnostics, java.time.Instant.now());
    }

    private static Prepared blocked(String reason) {
        return new Prepared(null, null, null, new ShaderpackGeneration(
                "blocked", "blocked", ShaderpackOutcome.BLOCKED, List.of("BLOCKED: " + reason), java.time.Instant.now()));
    }

    public record Prepared(ShaderpackManifest manifest, ShaderModule vertex, ShaderModule fragment,
                           ShaderpackGeneration generation) {
        public boolean executable() { return generation.executable() && vertex != null && fragment != null; }
    }
}
