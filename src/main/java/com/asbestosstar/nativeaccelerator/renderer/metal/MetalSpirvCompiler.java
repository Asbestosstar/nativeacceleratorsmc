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

import static org.lwjgl.util.spvc.Spvc.*;

/**
 * SPIR-V -> MSL translator using LWJGL's typed SPIRV-Cross binding.
 *
 * <p>The project carries {@code org.lwjgl:lwjgl-spvc:3.4.3} as a normal Maven dependency, matching
 * the LWJGL generation used by Minecraft 26.3. No reflective SPVC dispatch is used here.</p>
 */
final class MetalSpirvCompiler {
    record StageLayout(
            int[] uniformSlots,
            int[] samplerSlots,
            int[] storageBufferSlots,
            int uniformBufferCount,
            int samplerCount,
            int storageBufferCount,
            int pushConstantSlot) {}

    record Compiled(String source, String entryPoint, StageLayout layout) {}

    private MetalSpirvCompiler() {}

    static Compiled compile(
            BackendRenderPipeline.CreateInfo pipeline,
            BackendRenderPipeline.CreateInfo.Shader shader) throws ShaderCompileException {
        SpvModule module = shader.module();
        SpvModule.Reflection reflection = module.reflect();
        List<BindGroupLayout.UniformDescription> uniforms = pipeline.uniforms();

        int[] uniformSlots = filled(uniforms.size(), -1);
        int[] samplerSlots = filled(uniforms.size(), -1);
        int[] storageBufferSlots = filled(uniforms.size(), -1);
        int uniformCount = 0;
        int samplerCount = 0;
        int storageBufferCount = 0;

        for (SpvModule.Reflection.Descriptor descriptor : reflection.descriptors()) {
            int global = uniformIndex(uniforms, descriptor.name());
            if (global < 0) continue;
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

        int pushSlot = pipeline.pushConstantsSize() > 0 ? uniformCount++ : -1;
        if (uniformCount > 4) {
            throw new UnsupportedOperationException(
                    "SDL GPU exposes four uniform-buffer slots per shader stage; pipeline "
                            + pipeline.name() + " needs " + uniformCount + " in "
                            + shader.module().type().getName());
        }

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

            if (storageBufferCount > 0) {
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
                check(context,
                        spvc_compiler_options_set_bool(
                                options,
                                SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE,
                                true),
                        "enable native MSL texture buffers");
                check(context,
                        spvc_compiler_install_compiler_options(compiler, options),
                        "install MSL compiler options");
            }

            List<MetalTexelBufferLowering.Binding> texelBindings = new ArrayList<>();
            for (SpvModule.Reflection.Descriptor descriptor : reflection.descriptors()) {
                int global = uniformIndex(uniforms, descriptor.name());
                if (global < 0) continue;
                UniformType type = uniforms.get(global).type();

                SpvcMslResourceBinding binding = SpvcMslResourceBinding.calloc(stack);
                spvc_msl_resource_binding_init(binding);
                binding.stage(stage)
                        .desc_set(descriptor.descriptorSetIndex())
                        .binding(descriptor.binding());

                if (type == UniformType.UNIFORM_BUFFER) {
                    binding.msl_buffer(uniformSlots[global]);
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
                            uniformCount + storageSlot,
                            uniforms.get(global).gpuFormat()));
                }

                check(context,
                        spvc_compiler_msl_add_resource_binding(compiler, binding),
                        "remap MSL resource " + descriptor.name());
            }

            if (pushSlot >= 0) {
                SpvcMslResourceBinding push = SpvcMslResourceBinding.calloc(stack);
                spvc_msl_resource_binding_init(push);
                push.stage(stage)
                        .desc_set(SPVC_MSL_PUSH_CONSTANT_DESC_SET)
                        .binding(SPVC_MSL_PUSH_CONSTANT_BINDING)
                        .msl_buffer(pushSlot);
                check(context,
                        spvc_compiler_msl_add_resource_binding(compiler, push),
                        "remap MSL push constants");
            }

            PointerBuffer pSource = stack.mallocPointer(1);
            check(context, spvc_compiler_compile(compiler, pSource), "compile MSL");
            String source = MemoryUtil.memUTF8(pSource.get(0));
            source = MetalTexelBufferLowering.lower(source, texelBindings);

            String entryPoint = spvc_compiler_get_cleansed_entry_point_name(
                    compiler, shader.entryPoint(), stage);
            if (entryPoint == null || entryPoint.isBlank()) entryPoint = shader.entryPoint();

            return new Compiled(
                    source,
                    entryPoint,
                    new StageLayout(
                            uniformSlots,
                            samplerSlots,
                            storageBufferSlots,
                            uniformCount,
                            samplerCount,
                            storageBufferCount,
                            pushSlot));
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
