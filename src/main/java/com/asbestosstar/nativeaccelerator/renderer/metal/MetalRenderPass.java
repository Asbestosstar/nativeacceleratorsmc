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
import org.lwjgl.sdl.SDL_GPUViewport;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

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
    /** Mirrors GlRenderPass.anyUniformDirty: lets beforeDraw skip the per-uniform scan entirely. */
    private boolean anyUniformDirty;
    // Scissor state is deferred to draw time like GlRenderPass.scissorStateDirty: GUI code toggles
    // enable/disable scissor many times between draws, often with identical rectangles.
    private int appliedScissorX, appliedScissorY, appliedScissorW, appliedScissorH;
    private boolean scissorApplied;
    private int pendingScissorX, pendingScissorY, pendingScissorW, pendingScissorH;
    private boolean scissorDirty;
    private ByteBuffer pushConstants;
    private boolean pushConstantsDirty;
    private ByteBuffer vertexPackedUniforms;
    private ByteBuffer fragmentPackedUniforms;
    private boolean vertexPackedDirty;
    private boolean fragmentPackedDirty;
    private final MetalGpuBuffer[] boundVertexBuffers = new MetalGpuBuffer[16];
    private final long[] boundVertexOffsets = new long[16];
    private final boolean[] dirtyVertexBuffers = new boolean[16];
    /** Last CPU-shadow generation observed for each dynamically bound uniform buffer. */
    private long[] uniformEpochs = new long[0];
    private boolean[] epochTrackedUniforms = new boolean[0];
    private boolean hasEpochTrackedUniforms;
    private boolean dirtyIndexBuffer;
    private MetalGpuBuffer boundIndexBuffer;
    private IndexType boundIndexType;
    private final long[] boundVertexSamplerTextures = new long[16];
    private final long[] boundVertexSamplerHandles = new long[16];
    private final long[] boundFragmentSamplerTextures = new long[16];
    private final long[] boundFragmentSamplerHandles = new long[16];
    private final long[] boundVertexStorageBuffers = new long[16];
    private final long[] boundFragmentStorageBuffers = new long[16];
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
        flushScissor();
    }

    long handle() { return handle; }
    long tracePassId() { return tracePassId; }
    String tracePipelineName() { return pipeline == null ? "<none>" : pipeline.name(); }
    void traceEnd() { if (MetalTrace.enabled()) MetalTrace.log("PASS_END", "pass=" + tracePassId + " draws=" + traceDrawId + " pipeline=\"" + MetalTrace.safe(tracePipelineName()) + "\""); }

    @Override public void pushDebugGroup(Supplier<String> label) {
        // Labels are useful for diagnostics, but evaluating Supplier<String> and sending a native
        // debug command for every group is pure overhead in a production frame. Keep only the
        // lightweight terrain marker needed by performance telemetry when telemetry is enabled.
        if (!MetalTrace.enabled() && !MetalPerfCounters.ENABLED && !encoder.device().isDebuggingEnabled()) return;
        String text;
        try { text=label.get(); } catch(Throwable ignored){ text=""; }
        debugGroups.push(text == null ? "" : text);
        if (MetalTrace.enabled()) MetalTrace.log("DEBUG_GROUP_PUSH", "pass=" + tracePassId + " label=\"" + MetalTrace.safe(text) + "\" depth=" + debugGroups.size());
        if (encoder.device().isDebuggingEnabled()) {
            try { SDLGPU.SDL_PushGPUDebugGroup(encoder.commandHandle(), text); } catch(Throwable ignored){}
        }
    }
    @Override public void popDebugGroup() {
        if (!MetalTrace.enabled() && !MetalPerfCounters.ENABLED && !encoder.device().isDebuggingEnabled()) return;
        String popped = debugGroups.isEmpty() ? "" : debugGroups.pop();
        if (MetalTrace.enabled()) MetalTrace.log("DEBUG_GROUP_POP", "pass=" + tracePassId + " label=\"" + MetalTrace.safe(popped) + "\" depth=" + debugGroups.size());
        if (encoder.device().isDebuggingEnabled()) {
            try { SDLGPU.SDL_PopGPUDebugGroup(encoder.commandHandle()); } catch(Throwable ignored){}
        }
    }

    @Override public void setPipeline(BackendRenderPipeline value) {
        if (!(value instanceof MetalRenderPipeline p)) throw new IllegalArgumentException("Foreign pipeline bound to Metal pass");

        // Minecraft's frontend calls setPipeline() for nearly every GUI draw. The OpenGL backend
        // treats re-selecting the same pipeline as a cheap no-op; doing a native Metal bind plus
        // allocating fresh uniform arrays here made one pipeline bind/allocation pair per draw.
        if (pipeline == p) {
            MetalPerfCounters.pipelineBindSkip();
            return;
        }

        pipeline = p;
        if (MetalTrace.enabled()) MetalTrace.log("PIPELINE_BIND", "pass=" + tracePassId + " name=\"" + MetalTrace.safe(p.name()) + "\" handle=" + MetalTrace.hex(p.handle(hasDepthAttachment, targetMode)) + " depthAttachment=" + hasDepthAttachment + " targetMode=" + targetMode);

        // A genuinely different pipeline has a different logical binding namespace. FrontendRenderPass
        // replays the bindings needed by the new pipeline, so clear only on an actual transition.
        int uniformCount = p.uniformCount();
        if (uniforms.length == uniformCount) Arrays.fill(uniforms, null); else uniforms = new Object[uniformCount];
        if (dirtyUniforms.length != uniformCount) dirtyUniforms = new boolean[uniformCount];
        if (uniformEpochs.length != uniformCount) uniformEpochs = new long[uniformCount];
        if (epochTrackedUniforms.length != uniformCount) epochTrackedUniforms = new boolean[uniformCount];
        Arrays.fill(dirtyUniforms, true);
        Arrays.fill(uniformEpochs, 0L);
        Arrays.fill(epochTrackedUniforms, false);
        hasEpochTrackedUniforms = false;
        anyUniformDirty = uniformCount > 0;
        pushConstantsDirty = false;
        vertexPackedUniforms = packedBuffer(vertexPackedUniforms, p.vertexLayout().packedUniformBytes());
        fragmentPackedUniforms = packedBuffer(fragmentPackedUniforms, p.fragmentLayout().packedUniformBytes());
        vertexPackedDirty = p.vertexLayout().hasPackedUniforms();
        fragmentPackedDirty = p.fragmentLayout().hasPackedUniforms();

        // SDL's Metal backend has historically moved sampler/storage locations when pipeline layouts
        // change. Invalidate our same-pipeline binding caches on every real transition.
        Arrays.fill(boundVertexSamplerTextures, 0L);
        Arrays.fill(boundVertexSamplerHandles, 0L);
        Arrays.fill(boundFragmentSamplerTextures, 0L);
        Arrays.fill(boundFragmentSamplerHandles, 0L);
        Arrays.fill(boundVertexStorageBuffers, 0L);
        Arrays.fill(boundFragmentStorageBuffers, 0L);
        for (int i = 0; i < boundVertexBuffers.length; i++) {
            dirtyVertexBuffers[i] = boundVertexBuffers[i] != null;
        }
        dirtyIndexBuffer = boundIndexBuffer != null;

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
        Object previous = uniforms[index];
        if (sameUniformBinding(previous, value)
                && !(value instanceof GpuBufferSlice slice && uniformContentChanged(index, slice))) {
            return;
        }
        uniforms[index] = value;
        if (value instanceof GpuBufferSlice slice) rememberUniformEpoch(index, slice);
        else clearUniformEpoch(index);
        dirtyUniforms[index] = true;
        anyUniformDirty = true;
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
        if (pushConstants == null || pushConstants.capacity() < size) pushConstants = ByteBuffer.allocateDirect(size);
        ByteBuffer copy = pushConstants.duplicate();
        copy.clear().limit(size);
        copy.put(src).flip();
        pushConstants = copy.slice();
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_GPUViewport vp = SDL_GPUViewport.calloc(stack);
            vp.x((float)x);
            vp.y((float)y);
            vp.w((float)w);
            vp.h((float)h);
            vp.min_depth(0.0f);
            vp.max_depth(1.0f);
            SDLGPU.SDL_SetGPUViewport(handle, vp);
        }
    }
    /** Records the desired scissor; the native SDL call happens in flushScissor() only if it changed. */
    private void setScissor(int x,int y,int w,int h) {
        MetalCoordinatePolicy.Rect converted = MetalCoordinatePolicy.scissorRect(
                targetMode, outputHeight, x, y, w, h);
        if (MetalTrace.enabled()) MetalTrace.log("SCISSOR", "pass=" + tracePassId
                + " requested=" + x + "," + y + "," + w + "x" + h
                + " metal=" + converted.x() + "," + converted.y() + ","
                + converted.width() + "x" + converted.height()
                + " targetMode=" + targetMode);
        pendingScissorX = converted.x();
        pendingScissorY = converted.y();
        pendingScissorW = converted.width();
        pendingScissorH = converted.height();
        scissorDirty = !scissorApplied
                || pendingScissorX != appliedScissorX || pendingScissorY != appliedScissorY
                || pendingScissorW != appliedScissorW || pendingScissorH != appliedScissorH;
    }

    private void flushScissor() {
        if (!scissorDirty) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_Rect rect = SDL_Rect.calloc(stack)
                    .x(pendingScissorX).y(pendingScissorY).w(pendingScissorW).h(pendingScissorH);
            SDLGPU.SDL_SetGPUScissor(handle, rect);
        }
        appliedScissorX = pendingScissorX; appliedScissorY = pendingScissorY;
        appliedScissorW = pendingScissorW; appliedScissorH = pendingScissorH;
        scissorApplied = true;
        scissorDirty = false;
    }

    @Override public void setVertexBuffer(int slot, GpuBufferSlice slice) {
        if (slice == null) return;
        if (slot < 0 || slot >= boundVertexBuffers.length) {
            throw new IndexOutOfBoundsException("Metal vertex-buffer slot " + slot);
        }
        MetalGpuBuffer b = metal(slice.buffer());
        long offset = slice.offset();
        if (boundVertexBuffers[slot] == b && boundVertexOffsets[slot] == offset) {
            if (!dirtyVertexBuffers[slot]) MetalPerfCounters.vertexBindSkip();
            return;
        }
        boundVertexBuffers[slot] = b;
        boundVertexOffsets[slot] = offset;
        dirtyVertexBuffers[slot] = true;
        if (MetalTrace.enabled()) MetalTrace.log("VERTEX_BUFFER", "pass=" + tracePassId + " slot=" + slot + " handle=" + MetalTrace.hex(b.handle()) + " offset=" + offset + " length=" + slice.length());
    }
    @Override public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
        MetalGpuBuffer b=metal(buffer);
        if (boundIndexBuffer == b && boundIndexType == type) {
            if (!dirtyIndexBuffer) MetalPerfCounters.indexBindSkip();
            return;
        }
        boundIndexBuffer=b;
        boundIndexType=type;
        dirtyIndexBuffer=true;
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
        final boolean terrain=inTerrainGroup();
        final long pass=handle;
        // Decode in place: no per-command record allocation, and zero-sized commands (which cannot
        // produce fragments) never cost a native call. Layout matches MetalIndirectCommandDecoder.indexed().
        for(int i=0,base=0;i<drawCount;i++,base+=MetalIndirectCommandDecoder.INDEXED_DRAW_BYTES){
            int indexCount=bytes.getInt(base);
            int instanceCount=bytes.getInt(base+4);
            if(indexCount==0||instanceCount==0) continue;
            long perfStart=MetalPerfCounters.tic();
            SDLGPU.SDL_DrawGPUIndexedPrimitives(pass,indexCount,instanceCount,bytes.getInt(base+8),bytes.getInt(base+12),bytes.getInt(base+16));
            MetalPerfCounters.cpuIndirectCommand();
            MetalPerfCounters.draw(true,terrain,perfStart);
        }
    }

    /** MTLDrawPrimitivesIndirectArguments / VkDrawIndirectCommand: four 32-bit fields. */
    private void drawIndirectCpuFallback(MetalGpuBuffer buffer,long offset,int drawCount) {
        ByteBuffer bytes=buffer.shadowSlice(offset,Math.multiplyExact((long)drawCount,MetalIndirectCommandDecoder.DRAW_BYTES));
        final boolean terrain=inTerrainGroup();
        final long pass=handle;
        for(int i=0,base=0;i<drawCount;i++,base+=MetalIndirectCommandDecoder.DRAW_BYTES){
            int vertexCount=bytes.getInt(base);
            int instanceCount=bytes.getInt(base+4);
            if(vertexCount==0||instanceCount==0) continue;
            long perfStart=MetalPerfCounters.tic();
            SDLGPU.SDL_DrawGPUPrimitives(pass,vertexCount,instanceCount,bytes.getInt(base+8),bytes.getInt(base+12));
            MetalPerfCounters.cpuIndirectCommand();
            MetalPerfCounters.draw(false,terrain,perfStart);
        }
    }

    private void flushBufferBindings() {
        for (int slot = 0; slot < boundVertexBuffers.length; slot++) {
            if (!dirtyVertexBuffers[slot]) continue;
            MetalGpuBuffer buffer = boundVertexBuffers[slot];
            if (buffer == null) continue;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                SDL_GPUBufferBinding.Buffer binding = SDL_GPUBufferBinding.calloc(1, stack);
                binding.buffer(buffer.handle()).offset(Math.toIntExact(boundVertexOffsets[slot]));
                SDLGPU.SDL_BindGPUVertexBuffers(handle, slot, binding);
            }
            dirtyVertexBuffers[slot] = false;
            MetalPerfCounters.vertexBind();
        }
        if (dirtyIndexBuffer && boundIndexBuffer != null && boundIndexType != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                SDL_GPUBufferBinding binding = SDL_GPUBufferBinding.calloc(stack)
                        .set(boundIndexBuffer.handle(), 0);
                SDLGPU.SDL_BindGPUIndexBuffer(handle, binding, MetalConversions.indexType(boundIndexType));
            }
            dirtyIndexBuffer = false;
            MetalPerfCounters.indexBind();
        }
    }

    private void beforeDraw() {
        if(pipeline==null)throw new IllegalStateException("No Metal pipeline bound");
        flushScissor();
        flushBufferBindings();
        if (MetalTrace.enabled()) {
            int dirtyCount=0; for (boolean d : dirtyUniforms) if (d) dirtyCount++;
            MetalTrace.log("BEFORE_DRAW_BEGIN", "pass=" + tracePassId + " pipeline=\"" + MetalTrace.safe(pipeline.name()) + "\" dirtyUniforms=" + dirtyCount + " pushDirty=" + pushConstantsDirty + " groups=\"" + MetalTrace.safe(debugGroups) + "\"");
        }

        boolean packedVertexChanged = false;
        boolean packedFragmentChanged = false;

        // Vulkan's push-descriptor path requires every pipeline binding to be present on a draw.
        // Validate here before touching SDL state so a broken Sampler0/Projection/etc. fails with a
        // useful message instead of becoming a transparent/blank GUI draw.
        if (encoder.device().validateBindings()) validateUniformBindings();

        if (anyUniformDirty || hasEpochTrackedUniforms) for (int i = 0; i < uniforms.length; i++) {
            if (uniforms[i] instanceof GpuBufferSlice slice && uniformContentChanged(i, slice)) {
                rememberUniformEpoch(i, slice);
                dirtyUniforms[i] = true;
                anyUniformDirty = true;
            }
            if (!anyUniformDirty || i >= dirtyUniforms.length || !dirtyUniforms[i]) continue;
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
        anyUniformDirty = false;

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
        current.clear();
        MemoryUtil.memSet(current, 0);
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
        if (vs >= 0) bindStorageBufferStage(true, vs, buffer);
        if (fs >= 0) bindStorageBufferStage(false, fs, buffer);
    }

    private void bindStorageBufferStage(boolean vertexStage, int slot, MetalGpuBuffer buffer) {
        long bufferHandle = buffer.handle();
        long[] cache = vertexStage ? boundVertexStorageBuffers : boundFragmentStorageBuffers;
        if (slot >= 0 && slot < cache.length && cache[slot] == bufferHandle) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer buffers = stack.mallocPointer(1);
            buffers.put(0, bufferHandle);
            if (vertexStage) {
                SDLGPU.SDL_BindGPUVertexStorageBuffers(handle, slot, buffers);
            } else {
                SDLGPU.SDL_BindGPUFragmentStorageBuffers(handle, slot, buffers);
            }
            if (slot >= 0 && slot < cache.length) cache[slot] = bufferHandle;
            MetalPerfCounters.storageBind();
            if (MetalTrace.enabled()) MetalTrace.log("STORAGE_BIND", "pass=" + tracePassId + " method=" + (vertexStage ? "SDL_BindGPUVertexStorageBuffers" : "SDL_BindGPUFragmentStorageBuffers") + " slot=" + slot + " buffer=" + MetalTrace.hex(bufferHandle));
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
        if(vs>=0) bindSamplerStage(true,vs,view,sampler);
        if(fs>=0) bindSamplerStage(false,fs,view,sampler);
    }
    private void bindSamplerStage(boolean vertexStage,int slot,MetalTextureView view,MetalSampler sampler) {
        long textureHandle = view.metalTexture().handle();
        long samplerHandle = sampler.handleForView(view);
        long[] textures = vertexStage ? boundVertexSamplerTextures : boundFragmentSamplerTextures;
        long[] samplers = vertexStage ? boundVertexSamplerHandles : boundFragmentSamplerHandles;
        if (slot >= 0 && slot < textures.length && textures[slot] == textureHandle && samplers[slot] == samplerHandle) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_GPUTextureSamplerBinding.Buffer binding = SDL_GPUTextureSamplerBinding.calloc(1, stack);
            binding.texture(textureHandle).sampler(samplerHandle);
            if (vertexStage) {
                SDLGPU.SDL_BindGPUVertexSamplers(handle, slot, binding);
            } else {
                SDLGPU.SDL_BindGPUFragmentSamplers(handle, slot, binding);
            }
            if (slot >= 0 && slot < textures.length) { textures[slot] = textureHandle; samplers[slot] = samplerHandle; }
            MetalPerfCounters.samplerBind();
            if (MetalTrace.enabled()) MetalTrace.log("SAMPLER_BIND", "pass=" + tracePassId + " method=" + (vertexStage ? "SDL_BindGPUVertexSamplers" : "SDL_BindGPUFragmentSamplers") + " slot=" + slot + " texture=" + MetalTrace.hex(textureHandle) + " sampler=" + MetalTrace.hex(samplerHandle) + " baseMip=" + view.baseMipLevel() + " mipLevels=" + view.mipLevels());
        }
    }
    private boolean inTerrainGroup(){
        if (!MetalPerfCounters.ENABLED) return false;
        for(String group:debugGroups) if(group.startsWith("Terrain layer:")) return true;
        return false;
    }

    private boolean uniformContentChanged(int index, GpuBufferSlice slice) {
        if (index < 0 || index >= uniformEpochs.length) return false;
        MetalGpuBuffer buffer = metal(slice.buffer());
        return !epochTrackedUniforms[index] || uniformEpochs[index] != buffer.contentEpoch();
    }

    private void rememberUniformEpoch(int index, GpuBufferSlice slice) {
        if (index < 0 || index >= uniformEpochs.length) return;
        uniformEpochs[index] = metal(slice.buffer()).contentEpoch();
        epochTrackedUniforms[index] = true;
        hasEpochTrackedUniforms = true;
    }

    private void clearUniformEpoch(int index) {
        if (index < 0 || index >= epochTrackedUniforms.length) return;
        epochTrackedUniforms[index] = false;
        if (hasEpochTrackedUniforms) {
            hasEpochTrackedUniforms = false;
            for (boolean tracked : epochTrackedUniforms) {
                if (tracked) {
                    hasEpochTrackedUniforms = true;
                    break;
                }
            }
        }
    }

    private static boolean sameUniformBinding(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof GpuBufferSlice x && b instanceof GpuBufferSlice y) {
            return x.buffer() == y.buffer() && x.offset() == y.offset() && x.length() == y.length();
        }
        if (a instanceof TextureViewAndSampler x && b instanceof TextureViewAndSampler y) {
            return x.view() == y.view() && x.sampler() == y.sampler();
        }
        return false;
    }

    private static int slot(int[] slots,int i){return i<slots.length?slots[i]:-1;}
    private static MetalGpuBuffer metal(GpuBuffer b){if(!(b instanceof MetalGpuBuffer m))throw new IllegalArgumentException("Foreign buffer bound to Metal backend");return m;}
}

