package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * GLSL frontend boundary backed by RenderPearl's real compiler. This class deliberately does not
 * parse GLSL with regular expressions or claim support when the upstream compiler rejects a profile.
 */
public final class GlslFrontend implements ShaderFrontend {
    @Override
    public ShaderModule compile(ShaderSourceSpec source) {
        return compileInternal(source, null);
    }

    /** Compiles a bounded, include-expanded source snapshot through the real RenderPearl compiler. */
    public ShaderModule compile(ShaderSourceSpec source, GlslPreprocessor.SourceResolver resolver) {
        if (resolver == null) return compile(source);
        GlslPreprocessor.Result expanded = new GlslPreprocessor(resolver).process(
                source.sourceName(), source.defines());
        if (!expanded.successful()) {
            return new ShaderModule(source, null, emptyReflection(), expanded.diagnostics());
        }
        ShaderSourceSpec prepared = new ShaderSourceSpec(
                expanded.source(), source.sourceName(), source.version(), source.profile(), source.stage(),
                source.entryPoint(), source.defines(), expanded.dependencies().stream()
                        .map(GlslPreprocessor.Dependency::contentHash).toList(), source.compilerIdentity(),
                source.targetEnvironment());
        return compileInternal(prepared, expanded.diagnostics());
    }

    private ShaderModule compileInternal(ShaderSourceSpec source, List<ShaderDiagnostic> priorDiagnostics) {
        List<ShaderDiagnostic> diagnostics = new ArrayList<>(priorDiagnostics == null ? List.of() : priorDiagnostics);
        if (!"460".equals(source.version())) {
            diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR,
                    "URD vertical slice requires exact GLSL version 460", source.sourceName(), 0, 0));
            return new ShaderModule(source, null, emptyReflection(), diagnostics);
        }
        ShaderType type;
        try {
            type = ShaderType.valueOf(source.stage().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR,
                    "unsupported shader stage: " + source.stage(), source.sourceName(), 0, 0));
            return new ShaderModule(source, null, emptyReflection(), diagnostics);
        }
        // RenderPearl's first flag selects the clip-depth convention; its second flag is a
        // shader-draw-parameters capability, not a language/profile selector.
        boolean zeroToOneDepth = source.profile() == GlslProfile.VULKAN;
        try (GlslCompiler compiler = new GlslCompiler(zeroToOneDepth, false)) {
            String sourceId = source.sourceName().replace(' ', '_');
            // ShaderSourceSpec names may already be resource identifiers (the material loader uses
            // nativeaccelerator:urd/...). Do not prepend a second namespace before parsing.
            Identifier id = Identifier.parse(sourceId.indexOf(':') >= 0
                    ? sourceId
                    : "nativeaccelerator:" + sourceId);
            ShaderSource shaderSource = new InlineShaderSource(id, type, source.source());
            try (SpvModule module = compiler.compileToSpv(source.sourceName(), source.source(), type,
                    ShaderDefines.EMPTY, shaderSource)) {
                SpvModule.Reflection reflected = module.reflect();
                ByteBuffer nativeWords = module.spv().duplicate();
                ByteBuffer words = ByteBuffer.allocateDirect(nativeWords.remaining()).order(nativeWords.order());
                words.put(nativeWords).flip();
                return new ShaderModule(source, words.asReadOnlyBuffer(), convertReflection(reflected), diagnostics);
            }
        } catch (Exception | LinkageError | InternalError ex) {
            // Shaderc is a native compiler dependency. A plain test runner or a host without the
            // matching LWJGL native can still inspect source metadata, but must report compilation
            // as blocked rather than letting an UnsatisfiedLinkError abort the whole harness.
            diagnostics.add(new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    source.sourceName(), 0, 0));
            return new ShaderModule(source, null, emptyReflection(), diagnostics);
        }
    }

    private static ShaderReflection convertReflection(SpvModule.Reflection reflection) {
        List<ShaderReflection.InterfaceVariable> inputs = reflection.inputs().stream()
                .map(value -> new ShaderReflection.InterfaceVariable(value.name(), value.location(), typeName(value.type())))
                .toList();
        List<ShaderReflection.InterfaceVariable> outputs = reflection.outputs().stream()
                .map(value -> new ShaderReflection.InterfaceVariable(value.name(), value.location(), typeName(value.type())))
                .toList();
        List<ShaderReflection.Descriptor> descriptors = reflection.descriptors().stream()
                .map(value -> new ShaderReflection.Descriptor(value.name(), value.descriptorSetIndex(), value.binding(),
                        value.resourceType(), typeName(value.type())))
                .toList();
        int pushBytes = reflection.pushConstants().stream().mapToInt(SpvModule.Reflection.PushConstant::size).max().orElse(0);
        return new ShaderReflection(inputs, outputs, descriptors, pushBytes);
    }

    private static String typeName(SpvModule.Reflection.Type type) {
        return "base=" + type.baseType() + ",dims=" + type.dimensions() + ",vec=" + type.vectorSize()
                + ",arrays=" + type.arrayDimensions();
    }

    private static ShaderReflection emptyReflection() {
        return new ShaderReflection(List.of(), List.of(), List.of(), 0);
    }

    private static final class InlineShaderSource implements ShaderSource {
        private final Identifier id;
        private final ShaderType type;
        private final String source;

        private InlineShaderSource(Identifier id, ShaderType type, String source) {
            this.id = id;
            this.type = type;
            this.source = source;
        }

        @Override
        public String getShader(Identifier requested, ShaderType requestedType) {
            return requested.equals(id) && requestedType == type ? source : null;
        }

        @Override
        public CachedIncludeSource getInclude(Identifier requested) {
            return CachedIncludeSource.createError("includes are not available in inline URD sources: " + requested);
        }

        @Override
        public void close() {
        }
    }
}
