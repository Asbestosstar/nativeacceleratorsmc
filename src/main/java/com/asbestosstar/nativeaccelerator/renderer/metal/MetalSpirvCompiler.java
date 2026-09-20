package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.util.spvc.Spvc.*;

/**
 * SPIR-V -> MSL translator using LWJGL's typed SPIRV-Cross binding.
 *
 * <p>The project carries {@code org.lwjgl:lwjgl-spvc:3.4.3} as a normal Maven dependency, matching
 * the LWJGL generation used by Minecraft 26.3. No reflective SPVC dispatch is used here.</p>
 */
final class MetalSpirvCompiler {
    private static final AtomicBoolean GENERATOR_MARKER_REPORTED = new AtomicBoolean();
    record StageLayout(
            int[] uniformSlots,
            int[] packedUniformOffsets,
            int[] samplerSlots,
            int[] storageBufferSlots,
            int uniformBufferCount,
            int logicalUniformBufferCount,
            int samplerCount,
            int storageBufferCount,
            int pushConstantSlot,
            int pushConstantPackedOffset,
            int packedUniformBytes) {
        boolean hasPackedUniforms() { return packedUniformBytes > 0; }
    }

    record Compiled(String source, String entryPoint, StageLayout layout) {}

    private MetalSpirvCompiler() {}

    static Compiled compile(
            BackendRenderPipeline.CreateInfo pipeline,
            BackendRenderPipeline.CreateInfo.Shader shader) throws ShaderCompileException {
        return compile(pipeline, shader, false);
    }

    static Compiled compile(
            BackendRenderPipeline.CreateInfo pipeline,
            BackendRenderPipeline.CreateInfo.Shader shader,
            boolean targetVertexYFlip) throws ShaderCompileException {
        if (GENERATOR_MARKER_REPORTED.compareAndSet(false, true)) {
            System.out.println("[Native Accelerator] Metal generator marker: active-msl-layout-fix31");
        }
        SpvModule module = shader.module();
        SpvModule.Reflection reflection = module.reflect();
        List<BindGroupLayout.UniformDescription> uniforms = pipeline.uniforms();

        int[] uniformSlots = filled(uniforms.size(), -1);
        int[] packedUniformOffsets = filled(uniforms.size(), -1);
        int[] samplerSlots = filled(uniforms.size(), -1);
        int[] storageBufferSlots = filled(uniforms.size(), -1);
        int uniformCount = 0;
        int samplerCount = 0;
        int storageBufferCount = 0;

        for (SpvModule.Reflection.Descriptor descriptor : reflection.descriptors()) {
            int global = requireUniformIndex(pipeline, uniforms, descriptor);
            UniformType type = uniforms.get(global).type();
            if (type == UniformType.UNIFORM_BUFFER) {
                uniformSlots[global] = uniformCount++;
            } else if (type == UniformType.COMBINED_IMAGE_SAMPLER) {
                samplerSlots[global] = samplerCount++;
            } else if (type == UniformType.TEXEL_BUFFER) {
                GpuFormat format = uniforms.get(global).gpuFormat();
                if (format != GpuFormat.R8_SINT) {
                    throw new UnsupportedOperationException(
                            "Metal texel-buffer lowering currently supports R8_SINT only: "
                                    + descriptor.name() + " is " + format);
                }
                storageBufferSlots[global] = storageBufferCount++;
            }
        }

        int logicalPushSlot = pipeline.pushConstantsSize() > 0 ? uniformCount++ : -1;
        int logicalUniformCount = uniformCount;
        int physicalUniformCount = 0; // derived from active generated MSL, not declared pipeline resources
        int pushSlot = logicalPushSlot >= 0 && logicalPushSlot < 4 ? logicalPushSlot : -1;
        int pushPackedOffset = -1;
        int packedBytes = 0;

        long context = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pContext = stack.mallocPointer(1);
            check(0L, spvc_context_create(pContext), "create SPIRV-Cross context");
            context = pContext.get(0);

            ByteBuffer spirv = module.spv().duplicate().order(ByteOrder.nativeOrder());
            IntBuffer words = spirv.asIntBuffer();
            PointerBuffer pIr = stack.mallocPointer(1);
            check(context,
                    spvc_context_parse_spirv(context, words, words.remaining(), pIr),
                    "parse SPIR-V");

            PointerBuffer pCompiler = stack.mallocPointer(1);
            check(context,
                    spvc_context_create_compiler(
                            context,
                            SPVC_BACKEND_MSL,
                            pIr.get(0),
                            SPVC_CAPTURE_MODE_TAKE_OWNERSHIP,
                            pCompiler),
                    "create MSL compiler");
            long compiler = pCompiler.get(0);
            int stage = module.type() == ShaderType.VERTEX ? 0 : 4; // SpvExecutionModelVertex/Fragment

            boolean flipGeneratedTextureY = module.type() == ShaderType.VERTEX
                    && (MetalCoordinatePolicy.flipGeneratedTextureVertexY(pipeline.name()) || targetVertexYFlip);
            if (storageBufferCount > 0 || flipGeneratedTextureY) {
                PointerBuffer pOptions = stack.mallocPointer(1);
                check(context,
                        spvc_compiler_create_compiler_options(compiler, pOptions),
                        "create MSL compiler options");
                long options = pOptions.get(0);
                check(context,
                        spvc_compiler_options_set_uint(
                                options,
                                SPVC_COMPILER_OPTION_MSL_VERSION,
                                20100),
                        "set MSL 2.1");
                if (storageBufferCount > 0) {
                    check(context,
                            spvc_compiler_options_set_bool(
                                    options,
                                    SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE,
                                    true),
                            "enable native MSL texture buffers");
                }
                if (flipGeneratedTextureY) {
                    check(context,
                            spvc_compiler_options_set_bool(
                                    options,
                                    SPVC_COMPILER_OPTION_FLIP_VERTEX_Y,
                                    true),
                            "flip generated-texture vertex Y for Metal");
                    if (MetalCoordinatePolicy.flipGeneratedTextureVertexY(pipeline.name())) {
                        MetalCoordinatePolicy.report(pipeline.name());
                    }
                    if (targetVertexYFlip) {
                        System.out.println("[Native Accelerator] Metal target coordinate marker: pipeline="
                                + pipeline.name() + "; target=gui-item-atlas; vertexYFlip=true");
                    }
                }
                check(context,
                        spvc_compiler_install_compiler_options(compiler, options),
                        "install MSL compiler options");
            }

            List<MetalTexelBufferLowering.Binding> texelBindings = new ArrayList<>();
            for (SpvModule.Reflection.Descriptor descriptor : reflection.descriptors()) {
                int global = requireUniformIndex(pipeline, uniforms, descriptor);
                UniformType type = uniforms.get(global).type();

                SpvcMslResourceBinding binding = SpvcMslResourceBinding.calloc(stack);
                spvc_msl_resource_binding_init(binding);
                binding.stage(stage)
                        .desc_set(descriptor.descriptorSetIndex())
                        .binding(descriptor.binding());

                if (type == UniformType.UNIFORM_BUFFER) {
                    int logicalSlot = uniformSlots[global];
                    binding.msl_buffer(logicalSlot);
                } else if (type == UniformType.COMBINED_IMAGE_SAMPLER) {
                    binding.msl_texture(samplerSlots[global]);
                    binding.msl_sampler(samplerSlots[global]);
                } else if (type == UniformType.TEXEL_BUFFER) {
                    int storageSlot = storageBufferSlots[global];
                    int temporaryTextureSlot = samplerCount + storageSlot;
                    binding.msl_texture(temporaryTextureSlot);
                    texelBindings.add(new MetalTexelBufferLowering.Binding(
                            temporaryTextureSlot,
                            storageSlot,
                            uniforms.get(global).gpuFormat()));
                }

                check(context,
                        spvc_compiler_msl_add_resource_binding(compiler, binding),
                        "remap MSL resource " + descriptor.name());
            }

            if (logicalPushSlot >= 0) {
                SpvcMslResourceBinding push = SpvcMslResourceBinding.calloc(stack);
                spvc_msl_resource_binding_init(push);
                push.stage(stage)
                        .desc_set(SPVC_MSL_PUSH_CONSTANT_DESC_SET)
                        .binding(SPVC_MSL_PUSH_CONSTANT_BINDING)
                        .msl_buffer(logicalPushSlot);
                check(context,
                        spvc_compiler_msl_add_resource_binding(compiler, push),
                        "remap MSL push constants");
            }

            PointerBuffer pSource = stack.mallocPointer(1);
            check(context, spvc_compiler_compile(compiler, pSource), "compile MSL");
            String source = MemoryUtil.memUTF8(pSource.get(0));

            String entryPoint = spvc_compiler_get_cleansed_entry_point_name(
                    compiler, shader.entryPoint(), stage);
            if (entryPoint == null || entryPoint.isBlank()) entryPoint = shader.entryPoint();

            /*
             * SDL's Metal ABI is defined by the resources that actually survived SPIRV-Cross,
             * not by RenderPearl's superset pipeline layout. Compact both tables before shader
             * creation so [[buffer]], [[texture]] and [[sampler]] are consecutive from zero.
             */
            MetalUniformPacking.Result uniformsLayout = MetalUniformPacking.lower(
                    source, entryPoint, logicalUniformCount);
            source = uniformsLayout.source();
            physicalUniformCount = uniformsLayout.physicalUniformCount();
            packedBytes = uniformsLayout.packedBytes();

            for (int i = 0; i < uniformSlots.length; i++) {
                int logical = uniformSlots[i];
                if (logical < 0) continue;
                int physical = uniformsLayout.physicalSlot(logical);
                int packed = uniformsLayout.packedOffset(logical);
                uniformSlots[i] = physical;
                packedUniformOffsets[i] = packed;
            }
            if (logicalPushSlot >= 0) {
                pushSlot = uniformsLayout.physicalSlot(logicalPushSlot);
                pushPackedOffset = uniformsLayout.packedOffset(logicalPushSlot);
            }

            MetalSamplerLayout.Result samplerLayout = MetalSamplerLayout.lower(
                    source, entryPoint, samplerCount);
            source = samplerLayout.source();
            int physicalSamplerCount = samplerLayout.samplerCount();
            for (int i = 0; i < samplerSlots.length; i++) {
                int logical = samplerSlots[i];
                if (logical >= 0) samplerSlots[i] = samplerLayout.physicalSlot(logical);
            }
            samplerCount = physicalSamplerCount;

            // Texel buffers become SDL Metal storage buffers after the final active uniform table,
            // exactly as SDL_CreateGPUShader requires.
            MetalTexelBufferLowering.Result storageLayout = MetalTexelBufferLowering.lower(
                    source, texelBindings, physicalUniformCount, storageBufferCount);
            source = storageLayout.source();
            int physicalStorageBufferCount = storageLayout.storageBufferCount();
            for (int i = 0; i < storageBufferSlots.length; i++) {
                int logical = storageBufferSlots[i];
                if (logical >= 0) storageBufferSlots[i] = storageLayout.physicalSlot(logical);
            }
            storageBufferCount = physicalStorageBufferCount;

            if (logicalUniformCount > 0 || physicalSamplerCount > 0 || storageBufferCount > 0) {
                System.out.println("[Native Accelerator] Metal MSL resource layout: pipeline=" + pipeline.name()
                        + ", stage=" + shader.module().type().getName()
                        + ", uniforms=" + logicalUniformCount + "->" + physicalUniformCount
                        + ", activeUniformLogical=" + java.util.Arrays.toString(uniformsLayout.activeLogicalSlots())
                        + ", samplers=" + java.util.Arrays.toString(samplerLayout.activeLogicalSlots())
                        + "->" + physicalSamplerCount
                        + ", storage=" + storageBufferCount
                        + (uniformsLayout.packingApplied() ? ", overflowPackedBytes=" + packedBytes : ""));
            }

            return new Compiled(
                    source,
                    entryPoint,
                    new StageLayout(
                            uniformSlots,
                            packedUniformOffsets,
                            samplerSlots,
                            storageBufferSlots,
                            physicalUniformCount,
                            logicalUniformCount,
                            samplerCount,
                            storageBufferCount,
                            pushSlot,
                            pushPackedOffset,
                            packedBytes));
        } finally {
            if (context != 0L) {
                spvc_context_destroy(context);
            }
        }
    }

    static ByteBuffer utf8z(String source) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        ByteBuffer out = ByteBuffer.allocateDirect(bytes.length + 1);
        out.put(bytes).put((byte) 0).flip();
        return out;
    }


    private static int requireUniformIndex(BackendRenderPipeline.CreateInfo pipeline,
                                           List<BindGroupLayout.UniformDescription> uniforms,
                                           SpvModule.Reflection.Descriptor descriptor) {
        // RenderPearl's Vulkan backend binds pipeline.uniforms()[i] directly to descriptor binding i
        // in set 0. Use that ABI as the canonical mapping on Metal as well. Resource names are debug
        // information and SPIRV-Cross is free to cleanse/rename them, so name-only matching is not
        // robust enough for GUI samplers such as Sampler0.
        int binding = descriptor.binding();
        if (descriptor.descriptorSetIndex() == 0 && binding >= 0 && binding < uniforms.size()) {
            String expectedName = uniforms.get(binding).name();
            if (!expectedName.equals(descriptor.name())) {
                MetalTrace.log("SPIRV_BINDING_NAME_MISMATCH", "pipeline=\"" + MetalTrace.safe(String.valueOf(pipeline.name()))
                        + "\" binding=" + binding + " reflected=\"" + MetalTrace.safe(descriptor.name())
                        + "\" layout=\"" + MetalTrace.safe(expectedName) + "\"");
            }
            return binding;
        }

        // Keep a strict fallback for unusual/non-zero-set inputs so the failure is diagnostic rather
        // than a silently unbound Metal resource.
        int byName = uniformIndex(uniforms, descriptor.name());
        if (byName >= 0) return byName;
        throw new IllegalStateException("Active SPIR-V resource '" + descriptor.name()
                + "' (set=" + descriptor.descriptorSetIndex() + ", binding=" + descriptor.binding()
                + ") in pipeline " + pipeline.name()
                + " has no matching RenderPearl uniform description");
    }

    private static int uniformIndex(List<BindGroupLayout.UniformDescription> uniforms, String name) {
        for (int i = 0; i < uniforms.size(); i++) {
            if (uniforms.get(i).name().equals(name)) return i;
        }
        return -1;
    }

    private static int[] filled(int n, int value) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, value);
        return a;
    }

    private static void check(long context, int result, String action) {
        if (result == SPVC_SUCCESS) return;
        String detail = context == 0L ? "" : spvc_context_get_last_error_string(context);
        throw new IllegalStateException(action + " failed (spvc=" + result + "): "
                + (detail == null ? "" : detail));
    }
}
