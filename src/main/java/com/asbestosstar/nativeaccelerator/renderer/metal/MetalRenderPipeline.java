package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.sdl.SDL_GPUShaderCreateInfo;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class MetalRenderPipeline implements BackendRenderPipeline {
    private final MetalDevice device;
    private final long handle;
    private final MetalSpirvCompiler.StageLayout vertexLayout;
    private final MetalSpirvCompiler.StageLayout fragmentLayout;
    private final int uniformCount;
    private final int pushConstantSize;
    private final boolean triangleFan;
    private final AtomicBoolean closed = new AtomicBoolean();

    MetalRenderPipeline(MetalDevice device, CreateInfo info) throws Exception {
        long perfCompileStart = MetalPerfCounters.tic();
        this.device = device;
        this.uniformCount = info.uniforms().size();
        this.pushConstantSize = info.pushConstantsSize();
        this.triangleFan = info.primitiveTopology() == PrimitiveTopology.TRIANGLE_FAN;
        BackendRenderPipeline.CreateInfo.Shader vsDef = null, fsDef = null;
        for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
            if (shader.module().type() == ShaderType.VERTEX) vsDef = shader;
            else if (shader.module().type() == ShaderType.FRAGMENT) fsDef = shader;
        }
        if (vsDef == null || fsDef == null) throw new IllegalArgumentException("Metal graphics pipeline requires vertex and fragment shaders: " + info.name());

        MetalSpirvCompiler.Compiled vs = MetalSpirvCompiler.compile(info, vsDef);
        MetalSpirvCompiler.Compiled fs = MetalSpirvCompiler.compile(info, fsDef);
        this.vertexLayout = vs.layout();
        this.fragmentLayout = fs.layout();

        long vertexShader = 0L, fragmentShader = 0L;
        Object pipelineInfo = null, vbs = null, attrs = null, colorTargets = null;
        try {
            vertexShader = createShader(vs, true);
            fragmentShader = createShader(fs, false);
            pipelineInfo = MetalInterop.calloc("SDL_GPUGraphicsPipelineCreateInfo");
            MetalInterop.set(pipelineInfo, "vertex_shader", vertexShader);
            MetalInterop.set(pipelineInfo, "fragment_shader", fragmentShader);

            Object vertexInput = MetalInterop.get(pipelineInfo, "vertex_input_state");
            vbs = MetalInterop.calloc("SDL_GPUVertexBufferDescription", info.vertexBuffers().size());
            for (int i=0;i<info.vertexBuffers().size();i++) {
                CreateInfo.VertexBuffer vb = info.vertexBuffers().get(i);
                Object out = MetalInterop.get(vbs, i);
                MetalInterop.set(out, "slot", vb.bufferSlot());
                MetalInterop.set(out, "pitch", vb.stride());
                MetalInterop.set(out, "input_rate", MetalInterop.sdl(vb.stepRate() == 0
                        ? "SDL_GPU_VERTEXINPUTRATE_VERTEX" : "SDL_GPU_VERTEXINPUTRATE_INSTANCE"));
                MetalInterop.set(out, "instance_step_rate", 0);
            }
            attrs = MetalInterop.calloc("SDL_GPUVertexAttribute", info.attribBindings().size());
            for (int i=0;i<info.attribBindings().size();i++) {
                CreateInfo.AttribBinding a = info.attribBindings().get(i);
                Object out = MetalInterop.get(attrs, i);
                MetalInterop.set(out, "location", a.location());
                MetalInterop.set(out, "buffer_slot", a.bufferSlot());
                MetalInterop.set(out, "format", MetalConversions.vertexFormat(a.format()));
                MetalInterop.set(out, "offset", a.offset());
            }
            MetalInterop.set(vertexInput, "vertex_buffer_descriptions", vbs);
            MetalInterop.set(vertexInput, "num_vertex_buffers", info.vertexBuffers().size());
            MetalInterop.set(vertexInput, "vertex_attributes", attrs);
            MetalInterop.set(vertexInput, "num_vertex_attributes", info.attribBindings().size());

            MetalInterop.set(pipelineInfo, "primitive_type", MetalConversions.primitive(info.primitiveTopology()));
            Object raster = MetalInterop.get(pipelineInfo, "rasterizer_state");
            MetalInterop.set(raster, "fill_mode", MetalConversions.fillMode(info.polygonMode()));
            MetalInterop.set(raster, "cull_mode", MetalInterop.sdl(info.cull() ? "SDL_GPU_CULLMODE_BACK" : "SDL_GPU_CULLMODE_NONE"));
            MetalInterop.set(raster, "front_face", MetalInterop.sdl(MetalCoordinatePolicy.frontFace(info.name())));
            DepthStencilState depth = info.depthStencilState();
            MetalInterop.set(raster, "depth_bias_constant_factor", depth == null ? 0f : depth.depthBiasConstant());
            MetalInterop.set(raster, "depth_bias_clamp", 0f);
            MetalInterop.set(raster, "depth_bias_slope_factor", depth == null ? 0f : depth.depthBiasScaleFactor());
            MetalInterop.set(raster, "enable_depth_bias", depth != null && (depth.depthBiasConstant()!=0f || depth.depthBiasScaleFactor()!=0f));
            MetalInterop.set(raster, "enable_depth_clip", true);

            Object multi = MetalInterop.get(pipelineInfo, "multisample_state");
            MetalInterop.set(multi, "sample_count", MetalInterop.sdl("SDL_GPU_SAMPLECOUNT_1"));
            MetalInterop.set(multi, "sample_mask", 0);
            MetalInterop.set(multi, "enable_mask", false);

            Object ds = MetalInterop.get(pipelineInfo, "depth_stencil_state");
            MetalInterop.set(ds, "compare_op", depth == null ? MetalInterop.sdl("SDL_GPU_COMPAREOP_ALWAYS") : MetalConversions.compare(depth.depthTest()));
            MetalInterop.set(ds, "compare_mask", 0xFF);
            MetalInterop.set(ds, "write_mask", 0xFF);
            MetalInterop.set(ds, "enable_depth_test", depth != null);
            MetalInterop.set(ds, "enable_depth_write", depth != null && depth.writeDepth());
            MetalInterop.set(ds, "enable_stencil_test", false);

            Object targetInfo = MetalInterop.get(pipelineInfo, "target_info");
            List<ColorTargetState> targets = castTargets(info.colorTargetStates());
            colorTargets = MetalInterop.calloc("SDL_GPUColorTargetDescription", targets.size());
            for (int i=0;i<targets.size();i++) {
                ColorTargetState target = targets.get(i);
                Object out = MetalInterop.get(colorTargets, i);
                if (target == null) {
                    MetalInterop.set(out, "format", MetalInterop.sdl("SDL_GPU_TEXTUREFORMAT_INVALID"));
                    continue;
                }
                MetalInterop.set(out, "format", MetalConversions.textureFormat(target.format()));
                Object blend = MetalInterop.get(out, "blend_state");
                MetalInterop.set(blend, "color_write_mask", target.writeMask());
                MetalInterop.set(blend, "enable_color_write_mask", target.writeMask() != 15);
                if (target.blendFunction().isPresent()) applyBlend(blend, target.blendFunction().get());
                else MetalInterop.set(blend, "enable_blend", false);
            }
            MetalInterop.set(targetInfo, "color_target_descriptions", colorTargets);
            MetalInterop.set(targetInfo, "num_color_targets", targets.size());
            MetalInterop.set(targetInfo, "has_depth_stencil_target", depth != null);
            if (depth != null) MetalInterop.set(targetInfo, "depth_stencil_format", MetalConversions.textureFormat(GpuFormat.D32_FLOAT));

            this.handle = MetalInterop.sdlLong("SDL_CreateGPUGraphicsPipeline", device.handle(), pipelineInfo);
            if (handle == 0L) throw new IllegalStateException("SDL_CreateGPUGraphicsPipeline failed for " + info.name() + ": " + MetalInterop.lastSdlError());
        } finally {
            if (vertexShader != 0L) try { MetalInterop.sdlCall("SDL_ReleaseGPUShader", device.handle(), vertexShader); } catch(Throwable ignored){}
            if (fragmentShader != 0L) try { MetalInterop.sdlCall("SDL_ReleaseGPUShader", device.handle(), fragmentShader); } catch(Throwable ignored){}
            MetalInterop.free(colorTargets); MetalInterop.free(attrs); MetalInterop.free(vbs); MetalInterop.free(pipelineInfo);
        }
        MetalPerfCounters.pipelineCompile(perfCompileStart);
    }

    private long createShader(MetalSpirvCompiler.Compiled shader, boolean vertex) {
        /*
         * LWJGL deliberately does not expose SDL_GPUShaderCreateInfo.code_size(long) as an
         * instance setter. The safe code(ByteBuffer) binding owns the count relationship, while
         * ncode_size(address, value) is the explicit generated field setter. Also, entrypoint()
         * takes a null-terminated UTF-8 ByteBuffer rather than a Java String.
         *
         * Keep shader creation typed instead of routing these special generated fields through
         * the generic reflection bridge.
         */
        SDL_GPUShaderCreateInfo ci = SDL_GPUShaderCreateInfo.calloc();
        ByteBuffer code = MetalSpirvCompiler.utf8z(shader.source());
        ByteBuffer entrypoint = MetalSpirvCompiler.utf8z(shader.entryPoint());
        try {
            ci.code(code);
            // Preserve the exact byte count we supplied previously. utf8z() includes the final NUL.
            SDL_GPUShaderCreateInfo.ncode_size(ci.address(), code.remaining());
            ci.entrypoint(entrypoint);
            ci.format(MetalInterop.sdl("SDL_GPU_SHADERFORMAT_MSL"));
            ci.stage(MetalInterop.sdl(vertex ? "SDL_GPU_SHADERSTAGE_VERTEX" : "SDL_GPU_SHADERSTAGE_FRAGMENT"));
            ci.num_samplers(shader.layout().samplerCount());
            ci.num_storage_textures(0);
            ci.num_storage_buffers(shader.layout().storageBufferCount());
            ci.num_uniform_buffers(shader.layout().uniformBufferCount());

            long h = SDLGPU.SDL_CreateGPUShader(device.handle(), ci);
            if (h == 0L) {
                throw new IllegalStateException("SDL_CreateGPUShader failed: " + MetalInterop.lastSdlError());
            }
            return h;
        } finally {
            ci.free();
        }
    }

    private static void applyBlend(Object out, BlendFunction blend) {
        MetalInterop.set(out, "src_color_blendfactor", MetalConversions.blendFactor(blend.color().sourceFactor()));
        MetalInterop.set(out, "dst_color_blendfactor", MetalConversions.blendFactor(blend.color().destFactor()));
        MetalInterop.set(out, "color_blend_op", MetalConversions.blendOp(blend.color().op()));
        MetalInterop.set(out, "src_alpha_blendfactor", MetalConversions.blendFactor(blend.alpha().sourceFactor()));
        MetalInterop.set(out, "dst_alpha_blendfactor", MetalConversions.blendFactor(blend.alpha().destFactor()));
        MetalInterop.set(out, "alpha_blend_op", MetalConversions.blendOp(blend.alpha().op()));
        MetalInterop.set(out, "enable_blend", true);
    }

    @SuppressWarnings({"unchecked","rawtypes"}) private static List<ColorTargetState> castTargets(List input) { return (List<ColorTargetState>)input; }
    long handle(){ if(closed.get())throw new IllegalStateException("Pipeline closed"); return handle; }
    MetalSpirvCompiler.StageLayout vertexLayout(){ return vertexLayout; }
    MetalSpirvCompiler.StageLayout fragmentLayout(){ return fragmentLayout; }
    int uniformCount(){ return uniformCount; }
    int pushConstantSize(){ return pushConstantSize; }
    @Override public boolean isClosed(){ return closed.get(); }
    boolean triangleFan(){return triangleFan;}
    @Override public void close(){ if(closed.compareAndSet(false,true)) MetalInterop.sdlCall("SDL_ReleaseGPUGraphicsPipeline",device.handle(),handle); }
}
