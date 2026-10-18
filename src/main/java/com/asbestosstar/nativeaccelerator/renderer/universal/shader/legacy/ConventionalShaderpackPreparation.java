package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslFrontend;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslProfile;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderFrontend;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSource;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackGeneration;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackOutcome;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderSourceSpec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded, loader-neutral preparation of conventional wrapper-driven shader stages.
 *
 * <p>This deliberately stops before Minecraft resource creation. A compiler-native absence is
 * reported as BLOCKED and never yields a partially executable generation.</p>
 */
public final class ConventionalShaderpackPreparation {
    private final ShaderFrontend frontend;
    private final ShaderpackConventionalVariantScanner scanner;
    private final ShaderpackConventionalScanner profileScanner;
    private final ShaderpackRenderTargetParser targets;

    public ConventionalShaderpackPreparation() {
        this(new GlslFrontend(), new ShaderpackConventionalVariantScanner(),
                new ShaderpackConventionalScanner(), new ShaderpackRenderTargetParser());
    }

    public ConventionalShaderpackPreparation(ShaderFrontend frontend,
                                              ShaderpackConventionalVariantScanner scanner,
                                              ShaderpackRenderTargetParser targets) {
        this(frontend, scanner, new ShaderpackConventionalScanner(), targets);
    }

    public ConventionalShaderpackPreparation(ShaderFrontend frontend,
                                              ShaderpackConventionalVariantScanner scanner,
                                              ShaderpackConventionalScanner profileScanner,
                                              ShaderpackRenderTargetParser targets) {
        this.frontend = java.util.Objects.requireNonNull(frontend, "frontend");
        this.scanner = java.util.Objects.requireNonNull(scanner, "scanner");
        this.profileScanner = java.util.Objects.requireNonNull(profileScanner, "profileScanner");
        this.targets = java.util.Objects.requireNonNull(targets, "targets");
    }

    public Prepared prepare(ShaderPackSource source, String packId,
                            RendererBackendRuntime.Backend backend) {
        return prepare(source, packId, backend, ShaderpackDimension.UNKNOWN, List.of(), Map.of());
    }

    public Prepared prepare(ShaderPackSource source, String packId,
                            RendererBackendRuntime.Backend backend,
                            ShaderpackDimension dimension,
                            List<String> requestedProfiles,
                            Map<String, String> requestedOptions) {
        if (source == null || packId == null || packId.isBlank()) return blocked(packId, "source or pack id is missing");
        if (backend == null || backend == RendererBackendRuntime.Backend.NONE || backend == RendererBackendRuntime.Backend.OTHER) {
            return blocked(packId, "shaderpack backend is unavailable");
        }
        ShaderpackConventionalProfile profile = profileScanner.scan(source);
        List<String> profileNames = requestedProfiles == null ? List.of() : List.copyOf(requestedProfiles);
        Map<String, String> optionValues = requestedOptions == null ? Map.of() : Map.copyOf(requestedOptions);
        ConventionalShaderpackSelection selection = ConventionalShaderpackSelection.resolve(
                profile, dimension, profileNames, optionValues);
        ConventionalShaderpackGraphAdapter.Result graph = new ConventionalShaderpackGraphAdapter().build(profile);
        List<String> profileFailures = new ArrayList<>(selection.diagnostics());
        boolean supportedFullscreen = supportsFinalFullscreenOnly(profile, selection, dimension);
        if (!supportedFullscreen) {
            profileFailures.addAll(unsupportedProfile(profile));
            profileFailures.add("executable conventional profile requires one shared final.vsh/final.fsh pair, no extra programs, and a known dimension");
        }
        if (!graph.validation().valid()) {
            profileFailures.addAll(graph.validation().errors().stream().map(error -> "invalid pass graph: " + error).toList());
        }
        if (!graph.supported() && !supportedFullscreen && profileFailures.isEmpty()) {
            profileFailures.add("conventional pass graph contains features without a validated execution lifecycle");
        }
        if (selection.enabledPrograms().isEmpty()) {
            profileFailures.add("no conventional programs are enabled for the selected profile and dimension");
        }
        List<ShaderpackStageVariant> variants = scanner.scan(source).stream()
                .filter(variant -> variant.dimension() == dimension
                        || (variant.dimension() == ShaderpackDimension.UNKNOWN
                        && variant.program().equals("final")))
                .filter(variant -> selection.enabledPrograms().stream().anyMatch(name ->
                        name.endsWith("/" + variant.program()) || name.equals(variant.program())))
                .filter(variant -> variant.program().equals("final"))
                .toList();
        if (!profileFailures.isEmpty()) return unsupported(packId, source.contentHash(), profileFailures);
        if (variants.isEmpty()) return unsupported(packId, source.contentHash(), List.of("no selected conventional vertex/fragment wrappers found"));
        List<PreparedVariant> prepared = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>(profile.diagnostics());
        diagnostics.addAll(profileFailures.stream().map(reason -> "UNSUPPORTED: " + reason).toList());
        diagnostics.add("backend=" + backend.name().toLowerCase(Locale.ROOT));
        diagnostics.add("pack=" + packId);
        diagnostics.add("contentHash=" + source.contentHash());
        boolean blocked = false;
        boolean unsupported = false;
        for (ShaderpackStageVariant variant : variants) {
            try {
                String wrapper = source.readText(variant.wrapperPath());
                if (wrapper == null) throw new IllegalArgumentException("missing wrapper: " + variant.wrapperPath());
                Map<String, String> defines = new LinkedHashMap<>(variant.defines());
                defines.put(variant.stage().equals("vertex") ? "VERTEX" : "FRAGMENT", "1");
                String stagePath = variant.wrapperPath();
                var expanded = new com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslPreprocessor(
                        path -> Optional.ofNullable(source.readText(rooted(source, path))))
                        .process(stripRoot(source, stagePath), defines);
                if (!expanded.successful()) {
                    diagnostics.add(variant.key() + ": preprocessing failed: " + expanded.diagnostics());
                    unsupported = true;
                    continue;
                }
                List<ShaderpackRenderTarget> renderTargets = targets.parse(expanded.source());
                if (!renderTargets.isEmpty()) {
                    diagnostics.add(variant.key() + ": declared render targets are unsupported by the fullscreen candidate");
                    unsupported = true;
                    continue;
                }
                GlslProfile glslProfile = profile(expanded.source());
                ShaderSourceSpec spec = new ShaderSourceSpec(expanded.source(), stagePath, null, glslProfile,
                        variant.stage(), "main", defines,
                        expanded.dependencies().stream().map(com.asbestosstar.nativeaccelerator.renderer.universal.shader.GlslPreprocessor.Dependency::contentHash).toList(),
                        "renderpearl-glsl", glslProfile == GlslProfile.VULKAN ? "vulkan1.2" : "opengl");
                ShaderModule module = frontend.compile(spec);
                prepared.add(new PreparedVariant(variant, spec, module, renderTargets));
                diagnostics.add(variant.key() + ": targets=" + renderTargets.size());
                if (!module.isValid()) {
                    String text = module.diagnostics().toString();
                    blocked |= text.contains("Failed to load a library") || text.contains("UnsatisfiedLinkError") || text.contains("Could not load");
                    unsupported |= !blocked;
                }
            } catch (RuntimeException failure) {
                diagnostics.add(variant.key() + ": " + failure.getMessage());
                unsupported = true;
            }
        }
        ConventionalShaderpackFullscreenPlan plan = null;
        if (supportedFullscreen && !blocked && !unsupported && prepared.size() == 2) {
            PreparedVariant vertex = prepared.stream().filter(value -> value.variant().stage().equals("vertex"))
                    .findFirst().orElse(null);
            PreparedVariant fragment = prepared.stream().filter(value -> value.variant().stage().equals("fragment"))
                    .findFirst().orElse(null);
            if (vertex != null && fragment != null && vertex.module().isValid() && fragment.module().isValid()
                    && compatibleFullscreenReflection(vertex.module(), fragment.module())) {
                plan = new ConventionalShaderpackFullscreenPlan(dimension,
                        List.of(new ConventionalShaderpackFullscreenPlan.Program("final", vertex.module(), fragment.module())),
                        selection.optionValues(), source.contentHash());
            } else {
                unsupported = true;
                diagnostics.add("final fullscreen interface is not the validated single-color/no-resource contract");
            }
        }
        // A plan is not active support: only the live Minecraft-owned frame graph and validated
        // output readback may promote it to an executable generation.
        ShaderpackOutcome outcome = blocked ? ShaderpackOutcome.BLOCKED
                : unsupported ? ShaderpackOutcome.UNSUPPORTED : ShaderpackOutcome.FRONTEND_ONLY;
        if (outcome != ShaderpackOutcome.EXACT_TRANSLATION) {
            diagnostics.add(outcome == ShaderpackOutcome.FRONTEND_ONLY
                    ? "validated final fullscreen plan prepared; framegraph execution/readback adapter is not yet installed"
                    : "conventional generation not publishable");
        }
        return new Prepared(prepared, plan, new ShaderpackGeneration(packId, source.contentHash(), outcome, diagnostics, Instant.now()));
    }

    private static boolean supportsFinalFullscreenOnly(ShaderpackConventionalProfile profile,
                                                       ConventionalShaderpackSelection selection,
                                                       ShaderpackDimension dimension) {
        if (dimension == null || dimension == ShaderpackDimension.UNKNOWN
                || profile.format() == ShaderpackFormat.UNKNOWN
                || !profile.diagnostics().isEmpty() || !selection.diagnostics().isEmpty()
                || !profile.unsupportedFeatures().isEmpty() || profile.programs().size() != 1
                || selection.enabledPrograms().size() != 1 || !selection.enabledPrograms().contains("final")
                || !selection.optionValues().isEmpty()
                || profile.properties().keySet().stream().anyMatch(key -> key.startsWith("program.")
                        || key.startsWith("screen") || key.startsWith("profile.")
                        || key.startsWith("uniform.") || key.startsWith("variable."))) return false;
        ShaderpackProgram program = profile.programs().getFirst();
        if (!program.name().equals("final")) return false;
        long vertices = program.stages().stream().filter(path -> path.toLowerCase(Locale.ROOT).endsWith(".vsh")).count();
        long fragments = program.stages().stream().filter(path -> path.toLowerCase(Locale.ROOT).endsWith(".fsh")).count();
        return vertices == 1 && fragments == 1 && program.stages().size() == 2;
    }

    private static boolean compatibleFullscreenReflection(ShaderModule vertex, ShaderModule fragment) {
        var vertexReflection = vertex.reflection();
        var fragmentReflection = fragment.reflection();
        if (vertexReflection == null || fragmentReflection == null
                || !vertexReflection.descriptors().isEmpty() || !fragmentReflection.descriptors().isEmpty()
                || vertexReflection.pushConstantBytes() != 0 || fragmentReflection.pushConstantBytes() != 0
                || !vertexReflection.outputs().isEmpty()) return false;
        if (fragmentReflection.outputs().size() != 1 || fragmentReflection.outputs().getFirst().location() != 0) return false;
        // The current fullscreen draw has no vertex buffer and the shader pipeline exposes no
        // inter-stage varying contract. Reject those interfaces rather than relying on backend
        // pipeline validation after a candidate has already been selected.
        return vertexReflection.inputs().isEmpty() && fragmentReflection.inputs().isEmpty();
    }

    private static List<String> unsupportedProfile(ShaderpackConventionalProfile profile) {
        List<String> failures = new ArrayList<>();
        if (profile.format() == ShaderpackFormat.UNKNOWN) {
            failures.add("unrecognized conventional shaderpack profile");
        }
        boolean hasShadow = profile.programs().stream().anyMatch(program -> program.familyName().startsWith("shadow"));
        boolean hasDeferred = profile.programs().stream().anyMatch(program -> program.familyName().startsWith("deferred"));
        boolean hasGeneralWorld = profile.programs().stream().anyMatch(program -> program.isWorldVariant()
                && !program.familyName().startsWith("shadow") && !program.familyName().startsWith("deferred"));
        if (hasGeneralWorld || (profile.unsupportedFeatures().contains("world-stage-passes") && !hasShadow && !hasDeferred)) {
            failures.add("world-stage programs require renderer lifecycle support");
        }
        if (profile.unsupportedFeatures().contains("multiple-render-targets")) {
            failures.add("MRT output requires framebuffer attachment support");
        }
        if (hasShadow) failures.add("shadow passes require renderer lifecycle and shadow-map support");
        if (hasDeferred) failures.add("deferred passes require renderer lifecycle support");
        return List.copyOf(failures);
    }

    private static GlslProfile profile(String source) {
        return source.contains("#version 450") || source.contains("#version 460") ? GlslProfile.VULKAN : GlslProfile.OPENGL;
    }

    private static String rooted(ShaderPackSource source, String path) {
        String prefix = source.list().stream().anyMatch(value -> value.startsWith("shaders/")) ? "shaders/" : "";
        return prefix + path;
    }

    private static String stripRoot(ShaderPackSource source, String path) {
        String prefix = source.list().stream().anyMatch(value -> value.startsWith("shaders/")) ? "shaders/" : "";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private static Prepared blocked(String id, String reason) {
        return new Prepared(List.of(), null, new ShaderpackGeneration(id == null || id.isBlank() ? "blocked" : id,
                "blocked", ShaderpackOutcome.BLOCKED, List.of("BLOCKED: " + reason), Instant.now()));
    }

    private static Prepared unsupported(String id, String hash, String reason) {
        return unsupported(id, hash, List.of(reason));
    }

    private static Prepared unsupported(String id, String hash, List<String> reasons) {
        List<String> diagnostics = reasons.stream().map(reason -> "UNSUPPORTED: " + reason).toList();
        return new Prepared(List.of(), null, new ShaderpackGeneration(id, hash,
                ShaderpackOutcome.UNSUPPORTED, diagnostics, Instant.now()));
    }

    public record Prepared(List<PreparedVariant> variants,
                           ConventionalShaderpackFullscreenPlan fullscreenPlan,
                           ShaderpackGeneration generation) {
        public Prepared {
            variants = List.copyOf(variants == null ? List.of() : variants);
            java.util.Objects.requireNonNull(generation, "generation");
            if (generation.executable() && fullscreenPlan == null) {
                throw new IllegalArgumentException("executable conventional generation needs a validated pass plan");
            }
        }
        public boolean executable() { return generation.executable() && fullscreenPlan != null; }
    }

    public record PreparedVariant(ShaderpackStageVariant variant,
                                  ShaderSourceSpec source,
                                  ShaderModule module,
                                  List<ShaderpackRenderTarget> renderTargets) {
        public PreparedVariant {
            java.util.Objects.requireNonNull(variant, "variant");
            java.util.Objects.requireNonNull(source, "source");
            java.util.Objects.requireNonNull(module, "module");
            renderTargets = List.copyOf(renderTargets == null ? List.of() : renderTargets);
        }
    }
}
