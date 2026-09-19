package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.sdl.SDL_GPUBufferBinding;
import org.lwjgl.sdl.SDL_GPUTextureSamplerBinding;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.Supplier;

final class MetalRenderPass implements RenderPassBackend {
    private final MetalCommandEncoder encoder;
    private final long handle;
    private final RenderPass.RenderArea renderArea;
    private MetalRenderPipeline pipeline;
    private Object[] uniforms = new Object[0];
    private boolean[] dirtyUniforms = new boolean[0];
    private ByteBuffer pushConstants;
    private boolean pushConstantsDirty;
    private final MetalGpuBuffer[] boundVertexBuffers = new MetalGpuBuffer[16];
    private final long[] boundVertexOffsets = new long[16];
    private MetalGpuBuffer boundIndexBuffer;
    private IndexType boundIndexType;
    private final ArrayDeque<String> debugGroups = new ArrayDeque<>();

    MetalRenderPass(MetalCommandEncoder encoder, long handle, RenderPass.RenderArea renderArea) {
        this.encoder = encoder; this.handle = handle; this.renderArea = renderArea;
    }

    long handle() { return handle; }

    @Override public void pushDebugGroup(Supplier<String> label) {
        String text;
        try { text=label.get(); } catch(Throwable ignored){ text=""; }
        debugGroups.push(text == null ? "" : text);
        try { SDLGPU.SDL_PushGPUDebugGroup(encoder.commandHandle(), text); } catch(Throwable ignored){}
    }
    @Override public void popDebugGroup() {
        if(!debugGroups.isEmpty()) debugGroups.pop();
        try { SDLGPU.SDL_PopGPUDebugGroup(encoder.commandHandle()); } catch(Throwable ignored){}
    }

    @Override public void setPipeline(BackendRenderPipeline value) {
        if (!(value instanceof MetalRenderPipeline p)) throw new IllegalArgumentException("Foreign pipeline bound to Metal pass");
        if (pipeline == p) { MetalPerfCounters.pipelineBindSkip(); return; }
        pipeline = p;
        if (uniforms.length != p.uniformCount()) uniforms = Arrays.copyOf(uniforms, p.uniformCount());
        if (dirtyUniforms.length != uniforms.length) dirtyUniforms = Arrays.copyOf(dirtyUniforms, uniforms.length);
        Arrays.fill(dirtyUniforms, true);
        pushConstantsDirty = pushConstants != null;
        long perfStart=MetalPerfCounters.tic();
        SDLGPU.SDL_BindGPUGraphicsPipeline(handle, p.handle());
        MetalPerfCounters.pipelineBind(perfStart);
    }

    @Override public void setUniform(int index, Object value) {
        if (index < 0) return;
        if (index >= uniforms.length) uniforms = Arrays.copyOf(uniforms, index + 1);
        if (index >= dirtyUniforms.length) dirtyUniforms = Arrays.copyOf(dirtyUniforms, index + 1);
        uniforms[index] = value;
        dirtyUniforms[index] = true;
    }

    @Override public void pushConstants(ByteBuffer value) {
        if (pipeline == null) throw new IllegalStateException("Pipeline must be bound before push constants");
        int size = pipeline.pushConstantSize();
        ByteBuffer src = value.duplicate();
        src.limit(src.position() + size);
        ByteBuffer copy = ByteBuffer.allocateDirect(size); copy.put(src).flip(); pushConstants = copy;
        pushConstantsDirty = true;
    }

    @Override public void enableScissor(int x,int y,int width,int height) { setScissor(x,y,width,height); }
    @Override public void disableScissor() { setScissor(renderArea.x(),renderArea.y(),renderArea.width(),renderArea.height()); }
    private void setScissor(int x,int y,int w,int h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Rect rect = SDL_Rect.calloc(stack).x(x).y(y).w(w).h(h);
            SDLGPU.SDL_SetGPUScissor(handle, rect);
        }
    }

    @Override public void setVertexBuffer(int slot, GpuBufferSlice slice) {
        if (slice == null) return;
        MetalGpuBuffer b = metal(slice.buffer());
        long offset = slice.offset();
        if (slot >= 0 && slot < boundVertexBuffers.length && boundVertexBuffers[slot] == b && boundVertexOffsets[slot] == offset) {
            MetalPerfCounters.vertexBindSkip();
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_GPUBufferBinding.Buffer binding = SDL_GPUBufferBinding.calloc(1, stack);
            binding.buffer(b.handle()).offset(Math.toIntExact(offset));
            SDLGPU.SDL_BindGPUVertexBuffers(handle, slot, binding);
        }
        if (slot >= 0 && slot < boundVertexBuffers.length) { boundVertexBuffers[slot] = b; boundVertexOffsets[slot] = offset; }
        MetalPerfCounters.vertexBind();
    }
    @Override public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        MetalGpuBuffer b=metal(buffer);
        if (boundIndexBuffer == b && boundIndexType == type) { MetalPerfCounters.indexBindSkip(); return; }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_GPUBufferBinding binding = SDL_GPUBufferBinding.calloc(stack).set(b.handle(), 0);
            SDLGPU.SDL_BindGPUIndexBuffer(handle, binding, MetalConversions.indexType(type));
        }
        boundIndexBuffer=b; boundIndexType=type; MetalPerfCounters.indexBind();
    }

    @Override public void drawIndexed(int indexCount,int instanceCount,int firstIndex,int vertexOffset,int firstInstance) {
        beforeDraw(); long perfStart=MetalPerfCounters.tic(); SDLGPU.SDL_DrawGPUIndexedPrimitives(handle,indexCount,instanceCount,firstIndex,vertexOffset,firstInstance); MetalPerfCounters.draw(true,inTerrainGroup(),perfStart);
    }
    @Override public void multiDrawIndexed(IntBuffer p,int instanceCount,int firstInstance,int drawCount) { throw new UnsupportedOperationException("Direct multi-draw is intentionally disabled on the Metal backend"); }
    @Override public void multiDrawIndexed(PointerBuffer a,IntBuffer b,IntBuffer c,int drawCount) { throw new UnsupportedOperationException("Direct multi-draw is intentionally disabled on the Metal backend"); }
    @Override public void drawIndexedIndirect(GpuBufferSlice commands,int drawCount) {
        if (!encoder.device().indirectDrawSupported()) {
            throw new UnsupportedOperationException("Metal indirect draws are unavailable in MacFamily1 compatibility mode");
        }
        beforeDraw(); MetalGpuBuffer b=metal(commands.buffer()); SDLGPU.SDL_DrawGPUIndexedPrimitivesIndirect(handle,b.handle(),Math.toIntExact(commands.offset()),drawCount);
    }
    @Override public void draw(int vertexCount,int instanceCount,int firstVertex,int firstInstance) { beforeDraw();long perfStart=MetalPerfCounters.tic();SDLGPU.SDL_DrawGPUPrimitives(handle,vertexCount,instanceCount,firstVertex,firstInstance);MetalPerfCounters.draw(false,inTerrainGroup(),perfStart); }
    @Override public void multiDraw(IntBuffer p,int instanceCount,int firstInstance,int drawCount) { throw new UnsupportedOperationException("Direct multi-draw is intentionally disabled on the Metal backend"); }
    @Override public void multiDraw(IntBuffer a,IntBuffer b,int drawCount) { throw new UnsupportedOperationException("Direct multi-draw is intentionally disabled on the Metal backend"); }
    @Override public void drawIndirect(GpuBufferSlice commands,int drawCount) {
        if (!encoder.device().indirectDrawSupported()) {
            throw new UnsupportedOperationException("Metal indirect draws are unavailable in MacFamily1 compatibility mode");
        }
        beforeDraw();MetalGpuBuffer b=metal(commands.buffer());SDLGPU.SDL_DrawGPUPrimitivesIndirect(handle,b.handle(),Math.toIntExact(commands.offset()),drawCount);
    }
    @Override public void writeTimestamp(GpuQueryPool pool,int index) { if(pool instanceof MetalQueryPool p)p.write(index); }

    private void beforeDraw() {
        if(pipeline==null)throw new IllegalStateException("No Metal pipeline bound");
        for (int i = 0; i < uniforms.length; i++) {
            if (i >= dirtyUniforms.length || !dirtyUniforms[i]) continue;
            bindDirtyUniform(i, uniforms[i]);
            dirtyUniforms[i] = false;
        }
        if (pushConstantsDirty) {
            pushPushConstants();
            pushConstantsDirty = false;
        }
    }

    /** Bind only state that changed since the last draw or pipeline switch. */
    private void bindDirtyUniform(int index, Object value) {
        if (value instanceof GpuBufferSlice slice) {
            MetalGpuBuffer b = metal(slice.buffer());
            int vsUniform = slot(pipeline.vertexLayout().uniformSlots(), index);
            int fsUniform = slot(pipeline.fragmentLayout().uniformSlots(), index);
            if (vsUniform >= 0 || fsUniform >= 0) {
                ByteBuffer bytes = b.shadowSlice(slice.offset(), slice.length());
                if (vsUniform >= 0) { long t=MetalPerfCounters.tic(); SDLGPU.SDL_PushGPUVertexUniformData(encoder.commandHandle(), vsUniform, bytes); MetalPerfCounters.uniformPush(bytes.remaining(),inTerrainGroup(),t); }
                if (fsUniform >= 0) { long t=MetalPerfCounters.tic(); SDLGPU.SDL_PushGPUFragmentUniformData(encoder.commandHandle(), fsUniform, bytes); MetalPerfCounters.uniformPush(bytes.remaining(),inTerrainGroup(),t); }
            }

            int vsStorage = slot(pipeline.vertexLayout().storageBufferSlots(), index);
            int fsStorage = slot(pipeline.fragmentLayout().storageBufferSlots(), index);
            if (vsStorage >= 0 || fsStorage >= 0) {
                bindStorageBuffer(index, slice, b, vsStorage, fsStorage);
            }
            return;
        }
        if (value instanceof TextureViewAndSampler) {
            bindSampler(index, value);
        }
    }
    private void pushPushConstants() {
        if(pipeline==null||pushConstants==null)return;
        int vs=pipeline.vertexLayout().pushConstantSlot(),fs=pipeline.fragmentLayout().pushConstantSlot();
        if(vs>=0) { ByteBuffer b=pushConstants.duplicate(); long t=MetalPerfCounters.tic(); SDLGPU.SDL_PushGPUVertexUniformData(encoder.commandHandle(),vs,b); MetalPerfCounters.uniformPush(b.remaining(),inTerrainGroup(),t); }
        if(fs>=0) { ByteBuffer b=pushConstants.duplicate(); long t=MetalPerfCounters.tic(); SDLGPU.SDL_PushGPUFragmentUniformData(encoder.commandHandle(),fs,b); MetalPerfCounters.uniformPush(b.remaining(),inTerrainGroup(),t); }
    }
    private void bindStorageBuffer(int index, GpuBufferSlice slice, MetalGpuBuffer buffer, int vs, int fs) {
        // SDL's graphics-storage-buffer binding is whole-buffer only. RenderPearl's current
        // texel-buffer user (CloudFaces) binds the whole ring-buffer allocation, so preserve
        // exact semantics and reject a future sliced texel buffer instead of silently rebasing it.
        if (slice.offset() != 0L || slice.length() != buffer.size()) {
            throw new UnsupportedOperationException(
                    "Metal TEXEL_BUFFER lowering requires a whole GpuBuffer slice; got offset="
                            + slice.offset() + " length=" + slice.length() + " size=" + buffer.size());
        }
        if (vs >= 0) bindStorageBufferStage("SDL_BindGPUVertexStorageBuffers", vs, buffer);
        if (fs >= 0) bindStorageBufferStage("SDL_BindGPUFragmentStorageBuffers", fs, buffer);
    }

    private void bindStorageBufferStage(String method, int slot, MetalGpuBuffer buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer buffers = stack.mallocPointer(1);
            buffers.put(0, buffer.handle());
            if ("SDL_BindGPUVertexStorageBuffers".equals(method)) {
                SDLGPU.SDL_BindGPUVertexStorageBuffers(handle, slot, buffers);
            } else {
                SDLGPU.SDL_BindGPUFragmentStorageBuffers(handle, slot, buffers);
            }
            MetalPerfCounters.storageBind();
        }
    }

    private void bindSampler(int index,Object value) {
        if(!(value instanceof TextureViewAndSampler pair)||pipeline==null)return;
        if(!(pair.view() instanceof MetalTextureView view)||!(pair.sampler() instanceof MetalSampler sampler))throw new IllegalArgumentException("Foreign texture/sampler bound to Metal pipeline");
        int vs=slot(pipeline.vertexLayout().samplerSlots(),index),fs=slot(pipeline.fragmentLayout().samplerSlots(),index);
        if(vs>=0) bindSamplerStage("SDL_BindGPUVertexSamplers",vs,view,sampler);
        if(fs>=0) bindSamplerStage("SDL_BindGPUFragmentSamplers",fs,view,sampler);
    }
    private void bindSamplerStage(String method,int slot,MetalTextureView view,MetalSampler sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_GPUTextureSamplerBinding.Buffer binding = SDL_GPUTextureSamplerBinding.calloc(1, stack);
            binding.texture(view.metalTexture().handle()).sampler(sampler.handle());
            if ("SDL_BindGPUVertexSamplers".equals(method)) {
                SDLGPU.SDL_BindGPUVertexSamplers(handle, slot, binding);
            } else {
                SDLGPU.SDL_BindGPUFragmentSamplers(handle, slot, binding);
            }
            MetalPerfCounters.samplerBind();
        }
    }
    private boolean inTerrainGroup(){
        for(String group:debugGroups) if(group.startsWith("Terrain layer:")) return true;
        return false;
    }
    private static int slot(int[] slots,int i){return i<slots.length?slots[i]:-1;}
    private static MetalGpuBuffer metal(GpuBuffer b){if(!(b instanceof MetalGpuBuffer m))throw new IllegalArgumentException("Foreign buffer bound to Metal backend");return m;}
}
