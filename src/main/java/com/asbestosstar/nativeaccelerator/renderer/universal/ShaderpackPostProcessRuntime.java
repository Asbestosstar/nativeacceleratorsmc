package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderModule;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackRuntime;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackCatalog;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackDescriptor;
import com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackPreparation;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

/** Real, narrow fullscreen fixture bridge using Minecraft's existing device and main target. */
public final class ShaderpackPostProcessRuntime {
    private static final ShaderpackRuntime RUNTIME = new ShaderpackRuntime();
    private static volatile PreparedState preparedState;
    private static volatile String attemptedIdentity;
    private static volatile RendererBackendRuntime.Backend currentBackend;
    private static volatile GpuDevice currentDevice;
    private static volatile com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdDevice urdDevice;
    private static volatile ConventionalPreparedState conventionalState;

    private ShaderpackPostProcessRuntime() {
    }

    /** Performs source discovery and compiler preparation after Minecraft creates its device. */
    public static void backendCreated(RendererBackendRuntime.Backend backend, GpuDevice device,
                                      com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdDevice ignored) {
        currentBackend = backend;
        currentDevice = device;
        urdDevice = ignored;
        String requested = requestedPack();
        if (requested.isBlank() || "none".equalsIgnoreCase(requested) || "off".equalsIgnoreCase(requested)) {
            clearPreparedState();
            clearConventionalState();
            attemptedIdentity = null;
            RUNTIME.disable("vanilla shader selection");
            return;
        }
        if (!Boolean.getBoolean("nativeaccelerator.urd.enabled")) {
            clearPreparedState();
            clearConventionalState();
            attemptedIdentity = null;
            block(requested, "shaderpack runtime is disabled (set nativeaccelerator.urd.enabled=true to enable it)");
            return;
        }
        if (device == null || (Boolean.getBoolean("nativeaccelerator.urd.enabled") && ignored == null)) {
            clearPreparedState();
            clearConventionalState();
            attemptedIdentity = null;
            block(requested, device == null ? "renderer device is unavailable"
                    : "Universal Renderer Device backend is unavailable");
            return;
        }

        try {
            ShaderpackCatalog catalog = new ShaderpackCatalog();
            ShaderpackDescriptor descriptor = catalog.scan().stream()
                    .filter(value -> value.id().equals(requested)).findFirst().orElse(null);
            if (descriptor == null) {
                clearPreparedState();
                clearConventionalState();
                attemptedIdentity = null;
                RUNTIME.beginPreparation(requested);
                block(requested, "selected shaderpack was not found: " + requested);
                return;
            }
            String identity = descriptor.id() + ":" + descriptor.contentHash() + ":" + backend
                    + ":device@" + Integer.toHexString(System.identityHashCode(device));
            if (identity.equals(attemptedIdentity) && ((preparedState != null && preparedState.device == device)
                    || (conventionalState != null && conventionalState.device == device))) return;
            if (preparedState != null && (!preparedState.identity.equals(identity)
                    || preparedState.device != device)) clearPreparedState();
            if (conventionalState != null && (!conventionalState.identity.equals(identity)
                    || conventionalState.device != device)) clearConventionalState();
            RUNTIME.beginPreparation(descriptor.id());
            attemptedIdentity = identity;
            report("SHADERPACK_DISCOVERED: " + descriptor.id() + " hash=" + prefix(descriptor.contentHash()));
            if (com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackPreparationRouter.route(descriptor)
                    == com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackPreparationRoute.CONVENTIONAL) {
                attemptedIdentity = identity;
                try (var source = catalog.openSource(descriptor)) {
                    var conventional = new com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ConventionalShaderpackPreparation()
                            .prepare(source, descriptor.id(), backend,
                                    conventionalDimension(), conventionalProfiles(), conventionalOptions(descriptor));
                    report("SHADERPACK_CONVENTIONAL_PROFILE: "
                            + descriptor.conventionalProfile().format().name().toLowerCase(Locale.ROOT));
                    clearPreparedState();
                    clearConventionalState();
                    if (conventional.fullscreenPlan() != null
                            && conventional.generation().outcome() == com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackOutcome.FRONTEND_ONLY) {
                        conventionalState = new ConventionalPreparedState(identity, descriptor.id(), backend, device, conventional);
                        report("SHADERPACK_CONVENTIONAL_FINAL_PREPARED: framegraph=awaiting-execution");
                    } else {
                        String reason = String.join("; ", conventional.generation().diagnostics());
                        block(descriptor.id(), reason.isBlank()
                                ? "conventional shaderpack has no validated fullscreen candidate" : reason);
                    }
                }
                return;
            }
            clearConventionalState();
            ShaderpackPreparation.Prepared prepared = new ShaderpackPreparation().prepare(catalog, descriptor, backend);
            if (!prepared.executable()) {
                clearPreparedState();
                block(descriptor.id(), "shaderpack preparation: " + String.join("; ", prepared.generation().diagnostics()));
                return;
            }
            report("SHADERPACK_MANIFEST_ACCEPTED: " + prepared.manifest().id());
            report("SHADERPACK_SOURCES_HASHED: " + prefix(prepared.generation().contentHash()));
            preparedState = new PreparedState(identity, backend, device, prepared);
            report("SHADERPACK_SOURCES_PREPARED: compiler=renderpearl-glsl");
        } catch (Throwable failure) {
            clearPreparedState();
            block(requested, message(failure));
        }
    }

    /**
     * Adds the currently prepared conventional final pass to Minecraft's current frame graph.
     * The ResourceHandle is frame-scoped; it is captured only by the pass callback and never retained.
     */
    public static void prepareConventionalPass(FrameGraphBuilder graph, ResourceHandle<RenderTarget> main) {
        if (graph == null || main == null) return;
        ConventionalPreparedState state = conventionalState;
        if (state == null || !Boolean.getBoolean("nativeaccelerator.urd.enabled")) return;
        FramePass pass = graph.addPass("Native Accelerator conventional final");
        ResourceHandle<RenderTarget> output = pass.readsAndWrites(main);
        pass.executes(() -> {
            try {
                RenderTarget target = output.get();
                state.render(target, target);
            } catch (Throwable failure) {
                try {
                    state.restore(output.get());
                } catch (Throwable restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
                failConventional(state, "final fullscreen pass failed: " + message(failure));
            }
        });
    }

    /** Called from GameRenderer#applyPostEffects before vanilla post effects and before the GUI pass. */
    public static void renderMainTarget(GameRenderer renderer) {
        PreparedState state = preparedState;
        ConventionalPreparedState conventional = conventionalState;
        String requested = requestedPack();
        if (requested.isBlank() || "none".equalsIgnoreCase(requested) || "off".equalsIgnoreCase(requested)
                || !Boolean.getBoolean("nativeaccelerator.urd.enabled")) {
            clearPreparedState();
            clearConventionalState();
            attemptedIdentity = null;
            if (requested.isBlank() || "none".equalsIgnoreCase(requested) || "off".equalsIgnoreCase(requested)) {
                if (RUNTIME.state() != ShaderpackRuntime.State.DISABLED
                        || RUNTIME.attemptSnapshot().state() != ShaderpackRuntime.AttemptState.NOT_ATTEMPTED) {
                    RUNTIME.disable("vanilla shader selection");
                }
            } else {
                ShaderpackRuntime.AttemptSnapshot attempt = RUNTIME.attemptSnapshot();
                if (attempt.state() != ShaderpackRuntime.AttemptState.FAILED
                        || !attempt.packId().equals(requested)
                        || !attempt.diagnostics().contains("shaderpack runtime is disabled (set nativeaccelerator.urd.enabled=true to enable it)")) {
                    block(requested, "shaderpack runtime is disabled (set nativeaccelerator.urd.enabled=true to enable it)");
                }
            }
            return;
        }
        if (renderer == null) return;
        if (conventional != null && !conventional.packId.equals(requested)) {
            clearConventionalState();
            conventional = null;
        }
        if (state == null && conventional == null) {
            ShaderpackRuntime.AttemptSnapshot attempt = RUNTIME.attemptSnapshot();
            if (currentBackend != null && currentDevice != null
                    && (!attempt.packId().equals(requested)
                    || attempt.state() == ShaderpackRuntime.AttemptState.NOT_ATTEMPTED)) {
                backendCreated(currentBackend, currentDevice, urdDevice);
            }
            return;
        }
        if (state == null && conventional != null) {
            if (conventional.identity.endsWith(":device@" + Integer.toHexString(System.identityHashCode(currentDevice)))) return;
            clearConventionalState();
            conventional = null;
        }
        if (state != null && !state.prepared.manifest().id().equals(requested)) {
            clearPreparedState();
            attemptedIdentity = null;
            RendererBackendRuntime.Backend backend = currentBackend == null ? state.backend : currentBackend;
            GpuDevice device = currentDevice == null ? state.device : currentDevice;
            backendCreated(backend, device, urdDevice);
            return;
        }
        if (state == null) return;
        RenderTarget main = renderer.mainRenderTarget();
        if (main == null || main.getColorTextureView() == null) {
            clearPreparedState();
            block(state.prepared.manifest().id(), "Minecraft main render target is unavailable");
            return;
        }
        try {
            state.render(main);
        } catch (Throwable failure) {
            clearPreparedState();
            block(state.prepared.manifest().id(), "main-target pass failed: " + message(failure));
        }
    }

    public static ShaderpackRuntime runtime() {
        return RUNTIME;
    }

    private static String requestedPack() {
        String requested = System.getProperty("nativeaccelerator.shaderpack", "").trim();
        return requested.isBlank() ? NativeAcceleratorConfig.stringValue(ShaderpackCatalog.SELECTED_KEY, "") : requested;
    }

    private static com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackDimension conventionalDimension() {
        return switch (NativeAcceleratorConfig.stringValue("shaderpack.dimension", "unknown").toLowerCase(Locale.ROOT)) {
            case "overworld", "world0", "0" -> com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackDimension.OVERWORLD;
            case "nether", "world-1", "-1" -> com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackDimension.NETHER;
            case "end", "world1", "1" -> com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackDimension.END;
            default -> com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderpackDimension.UNKNOWN;
        };
    }

    private static List<String> conventionalProfiles() {
        String value = NativeAcceleratorConfig.stringValue("shaderpack.profiles", "");
        if (value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    private static java.util.Map<String, String> conventionalOptions(ShaderpackDescriptor descriptor) {
        String safePack = descriptor.id().replaceAll("[^A-Za-z0-9_-]", "_");
        String safeHash = descriptor.contentHash().isBlank() ? "unknown" : descriptor.contentHash();
        String prefix = "shaderpack.option." + safePack + "." + safeHash + ".";
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String key : NativeAcceleratorConfig.snapshot().stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                values.put(key.substring(prefix.length()), NativeAcceleratorConfig.stringValue(key, ""));
            }
        }
        return java.util.Map.copyOf(values);
    }

    private static void clearPreparedState() {
        PreparedState old = preparedState;
        preparedState = null;
        if (old != null) old.close();
    }

    private static void clearConventionalState() {
        ConventionalPreparedState old = conventionalState;
        conventionalState = null;
        if (old != null) old.close();
    }

    private static void failConventional(ConventionalPreparedState state, String reason) {
        if (conventionalState == state) {
            conventionalState = null;
            state.close();
            block(state.packId, reason);
        }
    }

    public static boolean conventionalPrepared() {
        return conventionalState != null;
    }

    static void activateConventionalForTest() {
        // Intentionally empty; the package-visible query exists only for integration assertions.
    }

    private static void block(String reason) {
        block(RUNTIME.attemptSnapshot().packId(), reason);
    }

    private static void block(String packId, String reason) {
        RUNTIME.disable("shaderpack attempt ended before activation");
        RUNTIME.block(packId, reason);
        report("BLOCKED: " + reason);
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getMessage() == null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static void report(String message) {
        System.out.println("[Native Accelerator] " + message);
    }

    private static String prefix(String value) {
        return value == null ? "unknown" : value.substring(0, Math.min(12, value.length()));
    }

    private static final class ConventionalPreparedState implements AutoCloseable {
        private final String identity;
        private final String packId;
        private final RendererBackendRuntime.Backend backend;
        private final GpuDevice device;
        private final com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ConventionalShaderpackPreparation.Prepared prepared;
        private RenderPipeline pipeline;
        private CompiledRenderPipeline compiled;
        private TextureTarget scratch;
        private int scratchWidth;
        private int scratchHeight;
        private boolean evidenceReported;

        private ConventionalPreparedState(String identity, String packId, RendererBackendRuntime.Backend backend,
                                           GpuDevice device,
                                           com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ConventionalShaderpackPreparation.Prepared prepared) {
            this.identity = identity;
            this.packId = packId;
            this.backend = backend;
            this.device = device;
            this.prepared = prepared;
        }

        private void render(RenderTarget source, RenderTarget main) {
            RenderSystem.assertOnRenderThread();
            if (source == null || source.getColorTexture() == null || main == null
                    || main.getColorTextureView() == null || main.width <= 0 || main.height <= 0) {
                throw new IllegalStateException("Minecraft main color target is unavailable");
            }
            ensurePipeline();
            ensureScratch(main.width, main.height);
            copy(source.getColorTexture(), scratch.getColorTexture());
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(
                    com.mojang.renderpearl.api.textures.FilterMode.NEAREST);
            var encoder = device.createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(() -> "Native Accelerator conventional " + packId,
                    main.getColorTextureView(), Optional.empty(), null, java.util.OptionalDouble.empty())) {
                pass.setPipeline(compiled);
                pass.setUniform("main_sampler", scratch.getColorTextureView(), sampler);
                pass.draw(3, 1, 0, 0);
            }
            encoder.submit();
            if (!evidenceReported) {
                validateReadback(main);
                evidenceReported = true;
                report("SHADERPACK_CONVENTIONAL_FINAL_SUBMITTED: pack=" + packId + " dimension="
                        + prepared.fullscreenPlan().dimension().name().toLowerCase(Locale.ROOT));
                report("SHADERPACK_CONVENTIONAL_FINAL_READBACK_SIZE_VALIDATED: " + main.width + "x" + main.height);
            }
        }

        private void restore(RenderTarget target) {
            if (target == null || scratch == null || scratch.getColorTexture() == null) return;
            copy(scratch.getColorTexture(), target.getColorTexture());
        }

        private void ensurePipeline() {
            if (compiled != null) return;
            var program = prepared.fullscreenPlan().programs().getFirst();
            ShaderModule vertex = program.vertex();
            ShaderModule fragment = program.fragment();
            Identifier vertexId = Identifier.parse("nativeaccelerator:conventional/" + packId + "/vertex");
            Identifier fragmentId = Identifier.parse("nativeaccelerator:conventional/" + packId + "/fragment");
            BindGroupLayout layout = BindGroupLayout.builder()
                    .withUniform("main_sampler", UniformType.COMBINED_IMAGE_SAMPLER).build();
            pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(Identifier.parse("nativeaccelerator:conventional/" + packId))
                    .withVertexShader(vertexId).withFragmentShader(fragmentId)
                    .withBindGroupLayout(layout).withColorTargetState(ColorTargetState.DEFAULT)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
            ShaderSource source = new InlineShaderSource(vertexId, fragmentId,
                    vertex.source().source(), fragment.source().source());
            try {
                compiled = device.compilePipeline(pipeline, source, ForkJoinPool.commonPool()).join().finishCompile();
            } finally {
                source.close();
            }
        }

        private void ensureScratch(int width, int height) {
            if (scratch != null && scratchWidth == width && scratchHeight == height) return;
            if (scratch != null) scratch.destroyBuffers();
            scratch = new TextureTarget("Native Accelerator conventional input", width, height, GpuFormat.RGBA8_UNORM, null);
            scratchWidth = width;
            scratchHeight = height;
        }

        private void copy(com.mojang.renderpearl.api.textures.GpuTexture source,
                          com.mojang.renderpearl.api.textures.GpuTexture destination) {
            var encoder = device.createCommandEncoder();
            encoder.copyTextureToTexture(source, destination, 0, 0, 0, 0, 0, scratchWidth, scratchHeight);
            encoder.submit();
        }

        private void validateReadback(RenderTarget target) {
            long bytes = Math.multiplyExact(Math.multiplyExact((long) target.width, target.height), 4L);
            GpuBuffer buffer = device.createBuffer(() -> "Native Accelerator conventional validation",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, bytes);
            GpuFence fence = null;
            try {
                var encoder = device.createCommandEncoder();
                encoder.copyTextureToBuffer(target.getColorTexture(), buffer, 0L, () -> { }, 0);
                fence = encoder.createFence();
                encoder.submit();
                if (!fence.awaitCompletion(GpuFence.NO_TIMEOUT)) throw new IllegalStateException("conventional output readback fence did not complete");
                try (var mapped = buffer.map(0L, bytes, true, false)) {
                    UrdPixelValidation.validateRgba8Readback(mapped.data().duplicate(), target.width, target.height);
                }
            } finally {
                if (fence != null) fence.close();
                buffer.close();
            }
        }

        @Override
        public void close() {
            if (scratch != null) {
                scratch.destroyBuffers();
                scratch = null;
            }
            compiled = null;
            pipeline = null;
        }
    }

    private static final class PreparedState {
        private final String identity;
        private final RendererBackendRuntime.Backend backend;
        private final GpuDevice device;
        private final ShaderpackPreparation.Prepared prepared;
        private RenderPipeline pipeline;
        private CompiledRenderPipeline compiled;
        private TextureTarget scratch;
        private int scratchWidth;
        private int scratchHeight;
        private boolean evidenceReported;
        private boolean activePublished;

        private PreparedState(String identity, RendererBackendRuntime.Backend backend,
                              GpuDevice device, ShaderpackPreparation.Prepared prepared) {
            this.identity = identity;
            this.backend = backend;
            this.device = device;
            this.prepared = prepared;
        }

        private void render(RenderTarget main) {
            RenderSystem.assertOnRenderThread();
            ensurePipeline();
            ensureScratch(main.width, main.height);

            copy(main.getColorTexture(), scratch.getColorTexture());
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(
                    com.mojang.renderpearl.api.textures.FilterMode.LINEAR);
            var encoder = device.createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Native Accelerator shaderpack " + prepared.manifest().id(),
                    main.getColorTextureView(), Optional.empty(),
                    main.hasDepth() ? main.getDepthTextureView() : null, java.util.OptionalDouble.empty())) {
                pass.setPipeline(compiled);
                pass.setUniform("main_sampler", scratch.getColorTextureView(), sampler);
                pass.draw(3, 1, 0, 0);
            }
            encoder.submit();
            if (!evidenceReported) {
                validateReadback(main);
                evidenceReported = true;
                RUNTIME.activate(prepared.generation());
                activePublished = true;
                report("SHADERPACK_PASS_SUBMITTED: backend=" + backend.name().toLowerCase(Locale.ROOT)
                        + " target=main");
                report("SHADERPACK_OUTPUT_VALIDATED: " + main.width + "x" + main.height);
                System.out.println("NATIVEACCELERATOR_SHADERPACK_FIXTURE_GPU_EVIDENCE: PASS");
            }
        }

        private void ensurePipeline() {
            if (compiled != null) return;
            ShaderModule vertex = prepared.vertex();
            ShaderModule fragment = prepared.fragment();
            Identifier vertexId = Identifier.parse("nativeaccelerator:shaderpack/" + prepared.manifest().id() + "/vertex");
            Identifier fragmentId = Identifier.parse("nativeaccelerator:shaderpack/" + prepared.manifest().id() + "/fragment");
            BindGroupLayout layout = BindGroupLayout.builder()
                    .withUniform("main_sampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build();
            pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withLocation(Identifier.parse("nativeaccelerator:shaderpack/" + prepared.manifest().id()))
                    .withVertexShader(vertexId)
                    .withFragmentShader(fragmentId)
                    .withBindGroupLayout(layout)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .build();
            ShaderSource source = new InlineShaderSource(vertexId, fragmentId, vertex.source().source(), fragment.source().source());
            try {
                compiled = device.compilePipeline(pipeline, source, ForkJoinPool.commonPool()).join().finishCompile();
            } finally {
                source.close();
            }
            report("SHADERPACK_PIPELINE_PREPARED: compiler=renderpearl-glsl target=main");
        }

        private void close() {
            if (scratch != null) {
                scratch.destroyBuffers();
                scratch = null;
                scratchWidth = 0;
                scratchHeight = 0;
            }
            compiled = null;
            pipeline = null;
        }

        private void ensureScratch(int width, int height) {
            if (scratch != null && scratchWidth == width && scratchHeight == height) return;
            if (scratch != null) scratch.destroyBuffers();
            scratch = new TextureTarget("Native Accelerator shaderpack input", width, height,
                    GpuFormat.RGBA8_UNORM, null);
            scratchWidth = width;
            scratchHeight = height;
        }

        private void copy(com.mojang.renderpearl.api.textures.GpuTexture source,
                          com.mojang.renderpearl.api.textures.GpuTexture destination) {
            var encoder = device.createCommandEncoder();
            encoder.copyTextureToTexture(source, destination, 0, 0, 0, 0, 0,
                    scratchWidth, scratchHeight);
            encoder.submit();
        }

        private void validateReadback(RenderTarget target) {
            long bytes = Math.multiplyExact(Math.multiplyExact((long) target.width, target.height), 4L);
            GpuBuffer buffer = device.createBuffer(() -> "Native Accelerator shaderpack validation",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, bytes);
            GpuFence fence = null;
            try {
                var encoder = device.createCommandEncoder();
                encoder.copyTextureToBuffer(target.getColorTexture(), buffer, 0L, () -> { }, 0);
                fence = encoder.createFence();
                encoder.submit();
                if (!fence.awaitCompletion(GpuFence.NO_TIMEOUT)) throw new IllegalStateException("output readback fence did not complete");
                try (var mapped = buffer.map(0L, bytes, true, false)) {
                    ByteBuffer data = mapped.data().duplicate();
                    UrdPixelValidation.validateRgba8Readback(data, target.width, target.height);
                }
            } finally {
                if (fence != null) fence.close();
                buffer.close();
            }
        }
    }

    private static final class InlineShaderSource implements ShaderSource {
        private final Identifier vertexId;
        private final Identifier fragmentId;
        private final String vertex;
        private final String fragment;

        private InlineShaderSource(Identifier vertexId, Identifier fragmentId, String vertex, String fragment) {
            this.vertexId = vertexId;
            this.fragmentId = fragmentId;
            this.vertex = vertex;
            this.fragment = fragment;
        }

        @Override
        public String getShader(Identifier id, ShaderType type) {
            if (type == ShaderType.VERTEX && vertexId.equals(id)) return vertex;
            if (type == ShaderType.FRAGMENT && fragmentId.equals(id)) return fragment;
            return null;
        }

        @Override
        public CachedIncludeSource getInclude(Identifier id) {
            return CachedIncludeSource.createError("shaderpack fixture has no unresolved include: " + id);
        }

        @Override
        public void close() {
        }
    }
}
