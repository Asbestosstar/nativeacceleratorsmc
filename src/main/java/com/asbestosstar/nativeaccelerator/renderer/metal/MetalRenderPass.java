package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.UniformType;
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
import java.util.concurrent.atomic.AtomicLong;

final class MetalRenderPass implements RenderPassBackend {
    private static final AtomicLong PASS_IDS = new AtomicLong();
    private final MetalCommandEncoder encoder;
    private final long tracePassId = PASS_IDS.incrementAndGet();
    private long traceDrawId;
    private final long handle;
    private final RenderPass.RenderArea renderArea;
    private final int outputWidth;
    private final int outputHeight;
    private final boolean hasDepthAttachment;
    private final MetalCoordinatePolicy.TargetMode targetMode;
    private MetalRenderPipeline pipeline;
    private Object[] uniforms = new Object[0];
    private boolean[] dirtyUniforms = new boolean[0];
    private ByteBuffer pushConstants;
    private boolean pushConstantsDirty;
    private ByteBuffer vertexPackedUniforms;
    private ByteBuffer fragmentPackedUniforms;
    private boolean vertexPackedDirty;
    private boolean fragmentPackedDirty;
    private final MetalGpuBuffer[] boundVertexBuffers = new MetalGpuBuffer[16];
    private final long[] boundVertexOffsets = new long[16];
    private MetalGpuBuffer boundIndexBuffer;
    private IndexType boundIndexType;
    private final ArrayDeque<String> debugGroups = new ArrayDeque<>();

    MetalRenderPass(MetalCommandEncoder encoder, long handle, RenderPass.RenderArea renderArea, int outputWidth, int outputHeight, boolean hasDepthAttachment, MetalCoordinatePolicy.TargetMode targetMode) {
        this.encoder = encoder; this.handle = handle; this.renderArea = renderArea;
        this.outputWidth = outputWidth; this.outputHeight = outputHeight;
        this.hasDepthAttachment = hasDepthAttachment;
        this.targetMode = targetMode == null ? MetalCoordinatePolicy.TargetMode.DEFAULT : targetMode;
        if (MetalTrace.enabled()) MetalTrace.log("PASS_BEGIN", "pass=" + tracePassId + " handle=" + MetalTrace.hex(handle) + " target=" + outputWidth + "x" + outputHeight + " area=" + renderArea.x() + "," + renderArea.y() + "," + renderArea.width() + "x" + renderArea.height() + " depth=" + hasDepthAttachment + " targetMode=" + this.targetMode + " cmd=" + MetalTrace.hex(encoder.commandHandle()));
        // Match both vanilla Vulkan and OpenGL: every render pass starts with an explicit full-target
        // viewport and the descriptor render-area scissor. Relying on SDL/Metal implicit state can
        // inherit stale world/pass dimensions and makes GUI atlas/button rendering order-dependent.
        setViewport(0, 0, outputWidth, outputHeight);
        setScissor(renderArea.x(), renderArea.y(), renderArea.width(), renderArea.height());
    }

    long handle() { return handle; }
    long tracePassId() { return tracePassId; }
    String tracePipelineName() { return pipeline == null ? "<none>" : pipeline.name(); }
    void traceEnd() { if (MetalTrace.enabled()) MetalTrace.log("PASS_END", "pass=" + tracePassId + " draws=" + traceDrawId + " pipeline=\"" + MetalTrace.safe(tracePipelineName()) + "\""); }

    @Override public void pushDebugGroup(Supplier<String> label) {
        String text;
        try { text=label.get(); } catch(Throwable ignored){ text=""; }
        debugGroups.push(text == null ? "" : text);
        if (MetalTrace.enabled()) MetalTrace.log("DEBUG_GROUP_PUSH", "pass=" + tracePassId + " label=\"" + MetalTrace.safe(text) + "\" depth=" + debugGroups.size());
        try { SDLGPU.SDL_PushGPUDebugGroup(encoder.commandHandle(), text); } catch(Throwable ignored){}
    }
    @Override public void popDebugGroup() {
        String popped = debugGroups.isEmpty() ? "" : debugGroups.pop();
        if (MetalTrace.enabled()) MetalTrace.log("DEBUG_GROUP_POP", "pass=" + tracePassId + " label=\"" + MetalTrace.safe(popped) + "\" depth=" + debugGroups.size());
        try { SDLGPU.SDL_PopGPUDebugGroup(encoder.commandHandle()); } catch(Throwable ignored){}
    }

    @Override public void setPipeline(BackendRenderPipeline value) {
        if (!(value instanceof MetalRenderPipeline p)) throw new IllegalArgumentException("Foreign pipeline bound to Metal pass");
        // Vulkan rebinds and resets the logical descriptor namespace even when the same pipeline
        // object is selected again. FrontendRenderPass then replays that pipeline's current values.
        // Treating this as a complete no-op can retain stale GUI samplers/uniforms across draws.
        pipeline = p;
        if (MetalTrace.enabled()) MetalTrace.log("PIPELINE_BIND", "pass=" + tracePassId + " name=\"" + MetalTrace.safe(p.name()) + "\" handle=" + MetalTrace.hex(p.handle(hasDepthAttachment, targetMode)) + " depthAttachment=" + hasDepthAttachment + " targetMode=" + targetMode);

        // Match RenderPearl's Vulkan and OpenGL backends: a pipeline switch starts with a completely
        // fresh logical uniform namespace.  Keeping values from the previous pipeline is incorrect
        // because logical index 0/1/etc. can refer to different bind-group resources in the next
        // pipeline.  The FrontendRenderPass immediately replays its current uniform map after
        // setPipeline(), so only resources valid for the new pipeline are restored.
        //
        // This is especially important for GUI rendering where Minecraft rapidly switches among
        // gui, gui_textured, text and premultiplied-alpha pipelines.  Stale Projection/Transforms,
        // sampler or storage bindings can otherwise manifest as clipped rows, missing item icons and
        // apparently flickering translucent layers.
        uniforms = new Object[p.uniformCount()];
        dirtyUniforms = new boolean[p.uniformCount()];
        Arrays.fill(dirtyUniforms, true);

        // Push constants are pipeline-specific too.  Do not replay bytes belonging to the previous
        // pipeline; FrontendRenderPass resets constantsPushed on every pipeline change and will push
        // the new constants before a draw that needs them.
        pushConstants = null;
        pushConstantsDirty = false;
        vertexPackedUniforms = packedBuffer(vertexPackedUniforms, p.vertexLayout().packedUniformBytes());
        fragmentPackedUniforms = packedBuffer(fragmentPackedUniforms, p.fragmentLayout().packedUniformBytes());
        vertexPackedDirty = p.vertexLayout().hasPackedUniforms();
        fragmentPackedDirty = p.fragmentLayout().hasPackedUniforms();
        long perfStart=MetalPerfCounters.tic();
        SDLGPU.SDL_BindGPUGraphicsPipeline(handle, p.handle(hasDepthAttachment, targetMode));
        MetalPerfCounters.pipelineBind(perfStart);
    }

    @Override public void setUniform(int index, Object value) {
        if (pipeline == null) throw new IllegalStateException("Pipeline must be bound before uniforms");
        if (index < 0 || index >= uniforms.length) {
            throw new IndexOutOfBoundsException("Metal uniform index " + index + " outside pipeline "
                    + pipeline.name() + " uniform count " + uniforms.length);
        }
        uniforms[index] = value;
        dirtyUniforms[index] = true;
        if (value instanceof GpuBufferSlice slice) {
            if (MetalTrace.enabled()) MetalTrace.log("UNIFORM_SET", "pass=" + tracePassId + " pipeline=\"" + (pipeline==null?"<none>":MetalTrace.safe(pipeline.name())) + "\" index=" + index + " kind=buffer handle=" + MetalTrace.hex(metal(slice.buffer()).handle()) + " offset=" + slice.offset() + " length=" + slice.length());
        } else if (value instanceof TextureViewAndSampler pair) {
            long tex = pair.view() instanceof MetalTextureView v ? v.metalTexture().handle() : 0L;
            long sampler = pair.sampler() instanceof MetalSampler m ? m.handle() : 0L;
            if (MetalTrace.enabled()) MetalTrace.log("UNIFORM_SET", "pass=" + tracePassId + " pipeline=\"" + (pipeline==null?"<none>":MetalTrace.safe(pipeline.name())) + "\" index=" + index + " kind=texture texture=" + MetalTrace.hex(tex) + " sampler=" + MetalTrace.hex(sampler));
        } else {
            if (MetalTrace.enabled()) MetalTrace.log("UNIFORM_SET", "pass=" + tracePassId + " pipeline=\"" + (pipeline==null?"<none>":MetalTrace.safe(pipeline.name())) + "\" index=" + index + " kind=" + (value==null?"null":value.getClass().getSimpleName()));
        }
    }

    @Override public void pushConstants(ByteBuffer value) {
        if (pipeline == null) throw new IllegalStateException("Pipeline must be bound before push constants");
        int size = pipeline.pushConstantSize();
        ByteBuffer src = value.duplicate();
        src.limit(src.position() + size);
        ByteBuffer copy = ByteBuffer.allocateDirect(size); copy.put(src).flip(); pushConstants = copy;
        pushConstantsDirty = true;
        if (MetalTrace.enabled()) MetalTrace.log("PUSH_CONSTANTS", "pass=" + tracePassId + " pipeline=\"" + MetalTrace.safe(pipeline.name()) + "\" bytes=" + size);
    }

    @Override public void enableScissor(int x,int y,int width,int height) { if (MetalTrace.enabled()) MetalTrace.log("SCISSOR_ENABLE", "pass=" + tracePassId + " rect=" + x + "," + y + "," + width + "x" + height); setScissor(x,y,width,height); }
    @Override public void disableScissor() {
        // RenderPearl disableScissor means "restore this pass' render area", not "disable clipping".
        if (MetalTrace.enabled()) MetalTrace.log("SCISSOR_DISABLE", "pass=" + tracePassId + " restore=" + renderArea.x() + "," + renderArea.y() + "," + renderArea.width() + "x" + renderArea.height());
        setScissor(renderArea.x(),renderArea.y(),renderArea.width(),renderArea.height());
    }
    private void setViewport(int x,int y,int w,int h) {
        if (w <= 0 || h <= 0) return;
        if (MetalTrace.enabled()) MetalTrace.log("VIEWPORT", "pass=" + tracePassId + " rect=" + x + "," + y + "," + w + "x" + h);
        Object viewport = MetalInterop.calloc("SDL_GPUViewport");
        try {
            MetalInterop.set(viewport,"x",(float)x);
            MetalInterop.set(viewport,"y",(float)y);
            MetalInterop.set(viewport,"w",(float)w);
            MetalInterop.set(viewport,"h",(float)h);
            MetalInterop.set(viewport,"min_depth",0.0f);
            MetalInterop.set(viewport,"max_depth",1.0f);
            MetalInterop.sdlCall("SDL_SetGPUViewport",handle,viewport);
        } finally { MetalInterop.free(viewport); }
    }
    private void setScissor(int x,int y,int w,int h) {
        MetalCoordinatePolicy.Rect converted = MetalCoordinatePolicy.scissorRect(
                targetMode, outputHeight, x, y, w, h);
        if (MetalTrace.enabled()) MetalTrace.log("SCISSOR", "pass=" + tracePassId
                + " requested=" + x + "," + y + "," + w + "x" + h
                + " metal=" + converted.x() + "," + converted.y() + ","
                + converted.width() + "x" + converted.height()
                + " targetMode=" + targetMode);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Rect rect = SDL_Rect.calloc(stack)
                    .x(converted.x()).y(converted.y()).w(converted.width()).h(converted.height());
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
        if (MetalTrace.enabled()) MetalTrace.log("VERTEX_BUFFER", "pass=" + tracePassId + " slot=" + slot + " handle=" + MetalTrace.hex(b.handle()) + " offset=" + offset + " length=" + slice.length());
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
        if (MetalTrace.enabled()) MetalTrace.log("INDEX_BUFFER", "pass=" + tracePassId + " handle=" + MetalTrace.hex(b.handle()) + " type=" + type);
    }

    @Override public void drawIndexed(int indexCount,int instanceCount,int firstIndex,int vertexOffset,int firstInstance) {
        if (pipeline != null && pipeline.triangleFan()) {
            throw new UnsupportedOperationException(
                    "Indexed triangle-fan expansion is not yet needed by vanilla Minecraft 26.3; "
                            + "the Metal compatibility path currently handles non-indexed fans");
        }
        beforeDraw(); long draw=MetalTrace.enabled()?++traceDrawId:0L; if (MetalTrace.enabled()) MetalTrace.log("DRAW_INDEXED", "pass=" + tracePassId + " draw=" + draw + " pipeline=\"" + MetalTrace.safe(pipeline.name()) + "\" indexCount=" + indexCount + " instances=" + instanceCount + " firstIndex=" + firstIndex + " vertexOffset=" + vertexOffset + " firstInstance=" + firstInstance); long perfStart=MetalPerfCounters.tic(); SDLGPU.SDL_DrawGPUIndexedPrimitives(handle,indexCount,instanceCount,firstIndex,vertexOffset,firstInstance); MetalPerfCounters.draw(true,inTerrainGroup(),perfStart);
    }
    @Override public void multiDrawIndexed(IntBuffer p,int instanceCount,int firstInstance,int drawCount) { throw new UnsupportedOperationException("Direct multi-draw is intentionally disabled on the Metal backend"); }
    @Override public void multiDrawIndexed(PointerBuffer a,IntBuffer b,IntBuffer c,int drawCount) { throw new UnsupportedOperationException("Direct multi-draw is intentionally disabled on the Metal backend"); }
    @Override public void drawIndexedIndirect(GpuBufferSlice commands,int drawCount) {
        if (pipeline != null && pipeline.triangleFan()) {
            throw new UnsupportedOperationException("Indexed indirect triangle-fan draws are not supported by the Metal compatibility path");
        }
        beforeDraw();
        MetalGpuBuffer b=metal(commands.buffer());
        if (MetalTrace.enabled()) MetalTrace.log("DRAW_INDEXED_INDIRECT", "pass=" + tracePassId + " draw=" + (++traceDrawId) + " pipeline=\"" + MetalTrace.safe(pipeline.name()) + "\" buffer=" + MetalTrace.hex(b.handle()) + " offset=" + commands.offset() + " count=" + drawCount + " native=" + encoder.device().nativeIndirectDrawSupported());
        if (encoder.device().nativeIndirectDrawSupported()) {
            try {
                SDLGPU.SDL_DrawGPUIndexedPrimitivesIndirect(handle,b.handle(),Math.toIntExact(commands.offset()),drawCount);
                MetalPerfCounters.nativeIndirectCall();
                return;
            } catch (Throwable failure) {
                encoder.device().blacklistNativeIndirect(failure);
            }
        }
        drawIndexedIndirectCpuFallback(b,commands.offset(),drawCount);
    }
    @Override public void draw(int vertexCount,int instanceCount,int firstVertex,int firstInstance) {
        beforeDraw();
        if (MetalTrace.enabled()) MetalTrace.log("DRAW", "pass=" + tracePassId + " draw=" + (++traceDrawId) + " pipeline=\"" + MetalTrace.safe(pipeline.name()) + "\" vertices=" + vertexCount + " instances=" + instanceCount + " firstVertex=" + firstVertex + " firstInstance=" + firstInstance);
        if (pipeline != null && pipeline.triangleFan()) {
            drawTriangleFan(vertexCount, instanceCount, firstVertex, firstInstance);
            return;
        }
        long perfStart=MetalPerfCounters.tic();
        SDLGPU.SDL_DrawGPUPrimitives(handle,vertexCount,instanceCount,firstVertex,firstInstance);
        MetalPerfCounters.draw(false,inTerrainGroup(),perfStart);
    }
    @Override public void multiDraw(IntBuffer p,int instanceCount,int firstInstance,int drawCount) { throw new UnsupportedOperationException("Direct multi-draw is intentionally disabled on the Metal backend"); }
    @Override public void multiDraw(IntBuffer a,IntBuffer b,int drawCount) { throw new UnsupportedOperationException("Direct multi-draw is intentionally disabled on the Metal backend"); }
    @Override public void drawIndirect(GpuBufferSlice commands,int drawCount) {
        if (pipeline != null && pipeline.triangleFan()) {
            throw new UnsupportedOperationException("Indirect triangle-fan draws are not supported by the Metal compatibility path");
        }
        beforeDraw();
        MetalGpuBuffer b=metal(commands.buffer());
        if (MetalTrace.enabled()) MetalTrace.log("DRAW_INDIRECT", "pass=" + tracePassId + " draw=" + (++traceDrawId) + " pipeline=\"" + MetalTrace.safe(pipeline.name()) + "\" buffer=" + MetalTrace.hex(b.handle()) + " offset=" + commands.offset() + " count=" + drawCount + " native=" + encoder.device().nativeIndirectDrawSupported());
        if (encoder.device().nativeIndirectDrawSupported()) {
            try {
                SDLGPU.SDL_DrawGPUPrimitivesIndirect(handle,b.handle(),Math.toIntExact(commands.offset()),drawCount);
                MetalPerfCounters.nativeIndirectCall();
                return;
            } catch (Throwable failure) {
                encoder.device().blacklistNativeIndirect(failure);
            }
        }
        drawIndirectCpuFallback(b,commands.offset(),drawCount);
    }
    @Override public void writeTimestamp(GpuQueryPool pool,int index) { if(pool instanceof MetalQueryPool p)p.write(index); }

    /**
     * RenderPearl/Vulkan indexed indirect layout is byte-for-byte compatible with Metal's
     * MTLDrawIndexedPrimitivesIndirectArguments: five 32-bit fields. When native graphics ICBs are
     * unavailable we keep Minecraft's efficient instanced terrain preparation and decode those
     * commands from MetalGpuBuffer's CPU shadow, issuing equivalent direct draws.
     */
    private void drawIndexedIndirectCpuFallback(MetalGpuBuffer buffer,long offset,int drawCount) {
        ByteBuffer bytes=buffer.shadowSlice(offset,Math.multiplyExact((long)drawCount,MetalIndirectCommandDecoder.INDEXED_DRAW_BYTES));
        boolean terrain=inTerrainGroup();
        for(int i=0;i<drawCount;i++){
            MetalIndirectCommandDecoder.IndexedDraw command=MetalIndirectCommandDecoder.indexed(bytes,i);
            long perfStart=MetalPerfCounters.tic();
            SDLGPU.SDL_DrawGPUIndexedPrimitives(handle,command.indexCount(),command.instanceCount(),command.firstIndex(),command.vertexOffset(),command.firstInstance());
            MetalPerfCounters.cpuIndirectCommand();
            MetalPerfCounters.draw(true,terrain,perfStart);
        }
    }

    /** MTLDrawPrimitivesIndirectArguments / VkDrawIndirectCommand: four 32-bit fields. */
    private void drawIndirectCpuFallback(MetalGpuBuffer buffer,long offset,int drawCount) {
        ByteBuffer bytes=buffer.shadowSlice(offset,Math.multiplyExact((long)drawCount,MetalIndirectCommandDecoder.DRAW_BYTES));
        boolean terrain=inTerrainGroup();
        for(int i=0;i<drawCount;i++){
            MetalIndirectCommandDecoder.Draw command=MetalIndirectCommandDecoder.draw(bytes,i);
            long perfStart=MetalPerfCounters.tic();
            SDLGPU.SDL_DrawGPUPrimitives(handle,command.vertexCount(),command.instanceCount(),command.firstVertex(),command.firstInstance());
            MetalPerfCounters.cpuIndirectCommand();
            MetalPerfCounters.draw(false,terrain,perfStart);
        }
    }

    private void beforeDraw() {
        if(pipeline==null)throw new IllegalStateException("No Metal pipeline bound");
        int dirtyCount=0; for (boolean d : dirtyUniforms) if (d) dirtyCount++;
        if (MetalTrace.enabled()) MetalTrace.log("BEFORE_DRAW_BEGIN", "pass=" + tracePassId + " pipeline=\"" + MetalTrace.safe(pipeline.name()) + "\" dirtyUniforms=" + dirtyCount + " pushDirty=" + pushConstantsDirty + " groups=\"" + MetalTrace.safe(debugGroups) + "\"");

        boolean packedVertexChanged = false;
        boolean packedFragmentChanged = false;

        // Vulkan's push-descriptor path requires every pipeline binding to be present on a draw.
        // Validate here before touching SDL state so a broken Sampler0/Projection/etc. fails with a
        // useful message instead of becoming a transparent/blank GUI draw.
        validateUniformBindings();

        for (int i = 0; i < uniforms.length; i++) {
            if (i >= dirtyUniforms.length || !dirtyUniforms[i]) continue;
            Object value = uniforms[i];

            if (value instanceof GpuBufferSlice slice) {
                MetalGpuBuffer b = metal(slice.buffer());
                ByteBuffer bytes = b.shadowSlice(slice.offset(), slice.length());

                int vsUniform = slot(pipeline.vertexLayout().uniformSlots(), i);
                int fsUniform = slot(pipeline.fragmentLayout().uniformSlots(), i);
                if (vsUniform >= 0) {
                    ByteBuffer copy = bytes.duplicate();
                    long t=MetalPerfCounters.tic();
                    if (MetalTrace.enabled()) MetalTrace.log("UNIFORM_PUSH_VS", "pass=" + tracePassId + " logical=" + i + " slot=" + vsUniform + " bytes=" + copy.remaining());
                    SDLGPU.SDL_PushGPUVertexUniformData(encoder.commandHandle(), vsUniform, copy);
                    MetalPerfCounters.uniformPush(copy.remaining(),inTerrainGroup(),t);
                }
                if (fsUniform >= 0) {
                    ByteBuffer copy = bytes.duplicate();
                    long t=MetalPerfCounters.tic();
                    if (MetalTrace.enabled()) MetalTrace.log("UNIFORM_PUSH_FS", "pass=" + tracePassId + " logical=" + i + " slot=" + fsUniform + " bytes=" + copy.remaining());
                    SDLGPU.SDL_PushGPUFragmentUniformData(encoder.commandHandle(), fsUniform, copy);
                    MetalPerfCounters.uniformPush(copy.remaining(),inTerrainGroup(),t);
                }

                int vsPacked = slot(pipeline.vertexLayout().packedUniformOffsets(), i);
                int fsPacked = slot(pipeline.fragmentLayout().packedUniformOffsets(), i);
                if (vsPacked >= 0) {
                    putPacked(vertexPackedUniforms, vsPacked, bytes, "vertex uniform " + i);
                    packedVertexChanged = true;
                }
                if (fsPacked >= 0) {
                    putPacked(fragmentPackedUniforms, fsPacked, bytes, "fragment uniform " + i);
                    packedFragmentChanged = true;
                }

                int vsStorage = slot(pipeline.vertexLayout().storageBufferSlots(), i);
                int fsStorage = slot(pipeline.fragmentLayout().storageBufferSlots(), i);
                if (vsStorage >= 0 || fsStorage >= 0) {
                    bindStorageBuffer(i, slice, b, vsStorage, fsStorage);
                }
            } else if (value instanceof TextureViewAndSampler) {
                bindSampler(i, value);
            }

            dirtyUniforms[i] = false;
        }

        if (pushConstantsDirty && pushConstants != null) {
            int vs=pipeline.vertexLayout().pushConstantSlot(),fs=pipeline.fragmentLayout().pushConstantSlot();
            if(vs>=0) {
                ByteBuffer b=pushConstants.duplicate();
                long t=MetalPerfCounters.tic();
                SDLGPU.SDL_PushGPUVertexUniformData(encoder.commandHandle(),vs,b);
                MetalPerfCounters.uniformPush(b.remaining(),inTerrainGroup(),t);
            }
            if(fs>=0) {
                ByteBuffer b=pushConstants.duplicate();
                long t=MetalPerfCounters.tic();
                SDLGPU.SDL_PushGPUFragmentUniformData(encoder.commandHandle(),fs,b);
                MetalPerfCounters.uniformPush(b.remaining(),inTerrainGroup(),t);
            }

            int vsPacked = pipeline.vertexLayout().pushConstantPackedOffset();
            int fsPacked = pipeline.fragmentLayout().pushConstantPackedOffset();
            if (vsPacked >= 0) {
                putPacked(vertexPackedUniforms, vsPacked, pushConstants, "vertex push constants");
                packedVertexChanged = true;
            }
            if (fsPacked >= 0) {
                putPacked(fragmentPackedUniforms, fsPacked, pushConstants, "fragment push constants");
                packedFragmentChanged = true;
            }
            pushConstantsDirty = false;
        }

        vertexPackedDirty |= packedVertexChanged;
        fragmentPackedDirty |= packedFragmentChanged;
        pushPackedUniforms();
        if (MetalTrace.enabled()) MetalTrace.log("BEFORE_DRAW_END", "pass=" + tracePassId + " pipeline=\"" + MetalTrace.safe(pipeline.name()) + "\"");
    }

    private void pushPackedUniforms() {
        if (pipeline.vertexLayout().hasPackedUniforms() && vertexPackedDirty) {
            ByteBuffer b = fullPacked(vertexPackedUniforms, pipeline.vertexLayout().packedUniformBytes());
            long t=MetalPerfCounters.tic();
            if (MetalTrace.enabled()) MetalTrace.log("PACKED_UNIFORM_VS", "pass=" + tracePassId + " slot=" + MetalUniformPacking.PACKED_SLOT + " bytes=" + b.remaining());
            SDLGPU.SDL_PushGPUVertexUniformData(
                    encoder.commandHandle(), MetalUniformPacking.PACKED_SLOT, b);
            MetalPerfCounters.uniformPush(b.remaining(),inTerrainGroup(),t);
            vertexPackedDirty = false;
        }
        if (pipeline.fragmentLayout().hasPackedUniforms() && fragmentPackedDirty) {
            ByteBuffer b = fullPacked(fragmentPackedUniforms, pipeline.fragmentLayout().packedUniformBytes());
            long t=MetalPerfCounters.tic();
            if (MetalTrace.enabled()) MetalTrace.log("PACKED_UNIFORM_FS", "pass=" + tracePassId + " slot=" + MetalUniformPacking.PACKED_SLOT + " bytes=" + b.remaining());
            SDLGPU.SDL_PushGPUFragmentUniformData(
                    encoder.commandHandle(), MetalUniformPacking.PACKED_SLOT, b);
            MetalPerfCounters.uniformPush(b.remaining(),inTerrainGroup(),t);
            fragmentPackedDirty = false;
        }
    }

    private static ByteBuffer packedBuffer(ByteBuffer current, int bytes) {
        if (bytes <= 0) return null;
        if (current == null || current.capacity() < bytes) {
            current = ByteBuffer.allocateDirect(bytes);
        }
        ByteBuffer clear = current.duplicate();
        clear.clear();
        while (clear.hasRemaining()) clear.put((byte)0);
        return current;
    }

    private static void putPacked(ByteBuffer packed, int offset, ByteBuffer source, String label) {
        if (packed == null) throw new IllegalStateException("Missing packed Metal uniform buffer for " + label);
        ByteBuffer src = source.duplicate();
        if (src.remaining() > MetalUniformPacking.PACK_STRIDE) {
            throw new UnsupportedOperationException(
                    label + " is " + src.remaining() + " bytes; packed Metal UBO stride is "
                            + MetalUniformPacking.PACK_STRIDE);
        }
        if (offset < 0 || offset + src.remaining() > packed.capacity()) {
            throw new IllegalArgumentException("Packed Metal uniform range outside allocation for " + label);
        }
        ByteBuffer dst = packed.duplicate();
        dst.position(offset).limit(offset + src.remaining());
        dst.put(src);
    }

    private static ByteBuffer fullPacked(ByteBuffer packed, int bytes) {
        ByteBuffer out = packed.duplicate();
        out.position(0).limit(bytes);
        return out.slice();
    }

    private void drawTriangleFan(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        int fanIndexCount = encoder.device().fanIndexBuffer().indexCountForVertices(vertexCount);
        if (fanIndexCount == 0 || instanceCount == 0) return;

        MetalGpuBuffer previous = boundIndexBuffer;
        IndexType previousType = boundIndexType;
        bindFanIndexBuffer();
        long perfStart = MetalPerfCounters.tic();
        SDLGPU.SDL_DrawGPUIndexedPrimitives(
                handle, fanIndexCount, instanceCount, 0, firstVertex, firstInstance);
        MetalPerfCounters.draw(true,inTerrainGroup(),perfStart);

        // Preserve RenderPearl state for callers that had already bound an index buffer.
        if (previous != null && previousType != null) {
            bindIndexBufferHandle(previous.handle(), MetalConversions.indexType(previousType));
            boundIndexBuffer = previous;
            boundIndexType = previousType;
        } else {
            // SDL has no explicit index-buffer unbind. Invalidate our cache so the next ordinary
            // indexed draw necessarily binds its own buffer.
            boundIndexBuffer = null;
            boundIndexType = null;
        }
    }

    private void bindFanIndexBuffer() {
        bindIndexBufferHandle(
                encoder.device().fanIndexBuffer().handle(),
                MetalInterop.sdl("SDL_GPU_INDEXELEMENTSIZE_32BIT"));
        boundIndexBuffer = null;
        boundIndexType = null;
    }

    private void bindIndexBufferHandle(long buffer, int indexType) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_GPUBufferBinding binding = SDL_GPUBufferBinding.calloc(stack).set(buffer, 0);
            SDLGPU.SDL_BindGPUIndexBuffer(handle, binding, indexType);
        }
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
            if (MetalTrace.enabled()) MetalTrace.log("STORAGE_BIND", "pass=" + tracePassId + " method=" + method + " slot=" + slot + " buffer=" + MetalTrace.hex(buffer.handle()));
        }
    }

    private void validateUniformBindings() {
        var descriptions = pipeline.uniforms();
        if (descriptions.size() != uniforms.length) {
            throw new IllegalStateException("Metal uniform state size mismatch for pipeline " + pipeline.name()
                    + ": state=" + uniforms.length + ", layout=" + descriptions.size());
        }
        for (int i = 0; i < descriptions.size(); i++) {
            var description = descriptions.get(i);
            Object value = uniforms[i];
            if (value == null) {
                String kind = description.type() == UniformType.COMBINED_IMAGE_SAMPLER ? "sampler " : "uniform ";
                throw new IllegalStateException("Missing " + kind + description.name()
                        + " (should be " + description.type() + ") in pipeline " + pipeline.name());
            }
            if ((description.type() == UniformType.UNIFORM_BUFFER || description.type() == UniformType.TEXEL_BUFFER)
                    && !(value instanceof GpuBufferSlice)) {
                throw new IllegalArgumentException("Metal binding " + description.name() + " in pipeline "
                        + pipeline.name() + " must be GpuBufferSlice for " + description.type()
                        + ", got " + value.getClass().getName());
            }
            if (description.type() == UniformType.COMBINED_IMAGE_SAMPLER
                    && !(value instanceof TextureViewAndSampler)) {
                throw new IllegalArgumentException("Metal binding " + description.name() + " in pipeline "
                        + pipeline.name() + " must be TextureViewAndSampler, got " + value.getClass().getName());
            }
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
            long samplerHandle = sampler.handleForView(view);
            binding.texture(view.metalTexture().handle()).sampler(samplerHandle);
            if ("SDL_BindGPUVertexSamplers".equals(method)) {
                SDLGPU.SDL_BindGPUVertexSamplers(handle, slot, binding);
            } else {
                SDLGPU.SDL_BindGPUFragmentSamplers(handle, slot, binding);
            }
            MetalPerfCounters.samplerBind();
            if (MetalTrace.enabled()) MetalTrace.log("SAMPLER_BIND", "pass=" + tracePassId + " method=" + method + " slot=" + slot + " texture=" + MetalTrace.hex(view.metalTexture().handle()) + " sampler=" + MetalTrace.hex(samplerHandle) + " baseMip=" + view.baseMipLevel() + " mipLevels=" + view.mipLevels());
        }
    }
    private boolean inTerrainGroup(){
        for(String group:debugGroups) if(group.startsWith("Terrain layer:")) return true;
        return false;
    }
    private static int slot(int[] slots,int i){return i<slots.length?slots[i]:-1;}
    private static MetalGpuBuffer metal(GpuBuffer b){if(!(b instanceof MetalGpuBuffer m))throw new IllegalArgumentException("Foreign buffer bound to Metal backend");return m;}
}

