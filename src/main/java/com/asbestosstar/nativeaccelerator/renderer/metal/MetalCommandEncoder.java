package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import org.joml.Vector4fc;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.sdl.SDL_GPUColorTargetInfo;
import org.lwjgl.sdl.SDL_GPUDepthStencilTargetInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class MetalCommandEncoder implements CommandEncoderBackend {
    private static final AtomicBoolean REGIONAL_CLEAR_MARKER_REPORTED = new AtomicBoolean();
    private static final AtomicLong ENCODER_IDS = new AtomicLong();
    private final MetalDevice device;
    private final long traceEncoderId = ENCODER_IDS.incrementAndGet();
    private long command;
    private long traceCommandSequence;
    private MetalRenderPass activePass;
    private MetalTransientMemory transientMemory;
    private final Set<Long> colorTargetsSeenThisSubmission = new HashSet<>();
    // RenderPearl/Vulkan fences mark the current submit; creating one must not itself split the frame.
    private long logicalCurrentSubmitIndex = 1L;
    private long logicalCompletedSubmitIndex = 0L;
    private final List<Long> retainedTextureTransfers = new ArrayList<>();

    MetalCommandEncoder(MetalDevice device) { this.device=device; this.transientMemory=new MetalTransientMemory(device); MetalTrace.log("ENCODER_CREATE", "encoder=" + traceEncoderId); }
    long traceEncoderId(){ return traceEncoderId; }
    MetalDevice device() { return device; }
    long commandHandle() {
        if(command==0L){ command=SDLGPU.SDL_AcquireGPUCommandBuffer(device.handle()); if(command!=0L){ traceCommandSequence++; MetalTrace.log("CMD_ACQUIRE", "encoder=" + traceEncoderId + " seq=" + traceCommandSequence + " cmd=" + MetalTrace.hex(command)); } if(command==0L)throw new IllegalStateException("SDL_AcquireGPUCommandBuffer failed: "+MetalInterop.lastSdlError()); }
        return command;
    }

    @Override public void submit() {
        MetalTrace.log("CMD_SUBMIT_REQUEST", "encoder=" + traceEncoderId + " seq=" + traceCommandSequence + " cmd=" + MetalTrace.hex(command) + " dirtyBuffers=" + device.hasDirtyBuffers() + " activePass=" + (activePass!=null));
        if(activePass!=null) throw new IllegalStateException("Cannot submit Metal command buffer inside a render pass");
        if(device.hasDirtyBuffers()) device.flushDirtyBuffers(commandHandle());
        if(command!=0L){
            long start=MetalPerfCounters.tic();
            if (device.mac1Compat()) {
                // Match RenderPearl/Vulkan fence semantics on the legacy Mac1 path: do not let
                // createFence() split a logical frame into many SDL submissions.  With one frame
                // in flight we conservatively wait for this real submit to complete, then all
                // logical fences associated with this submit index become complete together.
                long submittedIndex = logicalCurrentSubmitIndex;
                long nativeFence = SDLGPU.SDL_SubmitGPUCommandBufferAndAcquireFence(command);
                if (nativeFence == 0L) {
                    throw new IllegalStateException("SDL_SubmitGPUCommandBufferAndAcquireFence failed: " + MetalInterop.lastSdlError());
                }
                MetalTrace.log("MAC1_REAL_SUBMIT", "encoder=" + traceEncoderId + " logicalSubmit=" + submittedIndex
                        + " seq=" + traceCommandSequence + " cmd=" + MetalTrace.hex(command)
                        + " nativeFence=" + MetalTrace.hex(nativeFence));
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    org.lwjgl.PointerBuffer fences = stack.mallocPointer(1).put(0, nativeFence);
                    long waitStart = System.nanoTime();
                    boolean ok = SDLGPU.SDL_WaitForGPUFences(device.handle(), true, fences);
                    MetalTrace.log("MAC1_REAL_SUBMIT_WAIT", "encoder=" + traceEncoderId + " logicalSubmit=" + submittedIndex
                            + " ok=" + ok + " ns=" + (System.nanoTime() - waitStart));
                    if (!ok) throw new IllegalStateException("SDL_WaitForGPUFences failed: " + MetalInterop.lastSdlError());
                } finally {
                    SDLGPU.SDL_ReleaseGPUFence(device.handle(), nativeFence);
                }
                if (!retainedTextureTransfers.isEmpty()) {
                    MetalTrace.log("TEXTURE_TRANSFER_RELEASE_BATCH", "encoder=" + traceEncoderId + " count=" + retainedTextureTransfers.size());
                    for (long transfer : retainedTextureTransfers) MetalTransfers.releaseTransferBuffer(device.handle(), transfer);
                    retainedTextureTransfers.clear();
                }
                transientMemory.release();
                logicalCompletedSubmitIndex = submittedIndex;
                logicalCurrentSubmitIndex = submittedIndex + 1L;
            } else if (transientMemory.isEmpty()) {
                MetalTransfers.submit(command);
                transientMemory.release();
            } else {
                long fence = SDLGPU.SDL_SubmitGPUCommandBufferAndAcquireFence(command);
                if (fence == 0L) {
                    throw new IllegalStateException("SDL_SubmitGPUCommandBufferAndAcquireFence failed: " + MetalInterop.lastSdlError());
                }
                device.retireTransientMemory(transientMemory, fence);
            }
            MetalPerfCounters.submit(start);
            MetalTrace.log("CMD_SUBMITTED", "encoder=" + traceEncoderId + " seq=" + traceCommandSequence + " cmd=" + MetalTrace.hex(command) + " transientEmpty=" + transientMemory.isEmpty());
            command=0L;
            transientMemory=new MetalTransientMemory(device);
            colorTargetsSeenThisSubmission.clear();
        } else {
            transientMemory.release();
            transientMemory=new MetalTransientMemory(device);
            colorTargetsSeenThisSubmission.clear();
            if (device.mac1Compat()) {
                logicalCompletedSubmitIndex = logicalCurrentSubmitIndex;
                logicalCurrentSubmitIndex++;
            }
        }
        device.reapCompletedTransientMemory();
    }
    @Override public TransientMemory transientMemory(){return transientMemory;}

    @Override public RenderPassBackend createRenderPass(RenderPassDescriptor d) {
        MetalTrace.log("PASS_CREATE_REQUEST", "cmd=" + MetalTrace.hex(commandHandle()) + " colors=" + d.colorAttachments().size() + " depth=" + (d.depthAttachment()!=null) + " area=" + d.renderArea().x() + "," + d.renderArea().y() + "," + d.renderArea().width() + "x" + d.renderArea().height());
        long perfStart=MetalPerfCounters.tic();
        if(activePass!=null)throw new IllegalStateException("Nested Metal render pass");
        MetalCoordinatePolicy.TargetMode targetMode = MetalCoordinatePolicy.TargetMode.DEFAULT;
        // Upload all mapped vertex/index/storage/indirect buffers once, immediately before the frame's
        // render work. This replaces the old one-submit-per-map behavior.
        device.flushDirtyBuffers(commandHandle());
        SDL_GPUColorTargetInfo.Buffer colors=null;
        SDL_GPUDepthStencilTargetInfo depth=null;
        try {
            int count=d.colorAttachments().size();
            if(count>0){
                colors=(SDL_GPUColorTargetInfo.Buffer)MetalInterop.calloc("SDL_GPUColorTargetInfo",count);
                for(int i=0;i<count;i++){
                    RenderPassDescriptor.Attachment<java.util.Optional<Vector4fc>> a=d.colorAttachments().get(i);
                    if(a==null)throw new UnsupportedOperationException("SDL GPU Metal backend does not support sparse/unused color attachment slots");
                    if(!(a.textureView() instanceof MetalTextureView view))throw new IllegalArgumentException("Foreign color attachment");
                    long targetHandle=view.metalTexture().handle();
                    MetalCoordinatePolicy.TargetMode classified =
                            MetalCoordinatePolicy.classifyTarget(view.metalTexture().getLabel());
                    if (i == 0 || classified == MetalCoordinatePolicy.TargetMode.GUI_ITEM_ATLAS
                            || classified == MetalCoordinatePolicy.TargetMode.MAIN_TARGET) {
                        targetMode = classified;
                    }
                    Object out=MetalInterop.get(colors,i); MetalInterop.set(out,"texture",targetHandle); MetalInterop.set(out,"mip_level",view.baseMipLevel());MetalInterop.set(out,"layer_or_depth_plane",0);
                    boolean firstUse=colorTargetsSeenThisSubmission.add(targetHandle);
                    boolean forceFreshPresentTarget=firstUse
                            && device.forcePresentedSourceFirstClear()
                            && targetHandle==device.lastPresentedSourceHandle()
                            && a.clearValue().isEmpty();
                    boolean clear=a.clearValue().isPresent() || forceFreshPresentTarget;
                    MetalTrace.log("COLOR_ATTACHMENT", "slot=" + i + " texture=" + MetalTrace.hex(targetHandle)
                            + " mip=" + view.baseMipLevel() + " size=" + view.getWidth(0) + "x" + view.getHeight(0)
                            + " requestedLoad=" + (a.clearValue().isPresent()?"CLEAR":"LOAD")
                            + " actualLoad=" + (clear?"CLEAR":"LOAD")
                            + " firstUse=" + firstUse + " forcedFresh=" + forceFreshPresentTarget
                            + " cycle=" + (device.cycleClearedTargets() && clear));
                    MetalInterop.set(out,"load_op",MetalInterop.sdl(clear?"SDL_GPU_LOADOP_CLEAR":"SDL_GPU_LOADOP_LOAD"));
                    MetalInterop.set(out,"store_op",MetalInterop.sdl("SDL_GPU_STOREOP_STORE"));
                    // Mac1 runs one frame in flight. Avoid SDL texture cycling here by default;
                    // on legacy OCLP/NVIDIA drivers cycling can itself expose stale physical backing.
                    MetalInterop.set(out,"cycle",device.cycleClearedTargets() && clear);
                    if(a.clearValue().isPresent()) {
                        setColor(MetalInterop.get(out,"clear_color"),a.clearValue().get());
                    } else if(forceFreshPresentTarget) {
                        setColor(MetalInterop.get(out,"clear_color"),new org.joml.Vector4f(0f,0f,0f,1f));
                        MetalTrace.log("STALE_SOURCE_CLEAR", "texture=" + MetalTrace.hex(targetHandle));
                    }
                }
            }
            if(d.depthAttachment()!=null){
                var a=d.depthAttachment(); if(!(a.textureView() instanceof MetalTextureView view))throw new IllegalArgumentException("Foreign depth attachment");
                depth=(SDL_GPUDepthStencilTargetInfo)MetalInterop.calloc("SDL_GPUDepthStencilTargetInfo"); MetalInterop.set(depth,"texture",view.metalTexture().handle());
                MetalTrace.log("DEPTH_ATTACHMENT", "texture=" + MetalTrace.hex(view.metalTexture().handle()) + " mip=" + view.baseMipLevel() + " size=" + view.getWidth(0) + "x" + view.getHeight(0) + " load=" + (a.clearValue().isPresent()?"CLEAR":"LOAD") + " cycle=false");
                MetalInterop.set(depth,"clear_depth",(float)a.clearValue().orElse(1.0));
                MetalInterop.set(depth,"load_op",MetalInterop.sdl(a.clearValue().isPresent()?"SDL_GPU_LOADOP_CLEAR":"SDL_GPU_LOADOP_LOAD"));MetalInterop.set(depth,"store_op",MetalInterop.sdl("SDL_GPU_STOREOP_STORE"));
                MetalInterop.set(depth,"stencil_load_op",MetalInterop.sdl("SDL_GPU_LOADOP_DONT_CARE"));MetalInterop.set(depth,"stencil_store_op",MetalInterop.sdl("SDL_GPU_STOREOP_DONT_CARE"));
                MetalInterop.set(depth,"cycle",device.cycleClearedTargets() && a.clearValue().isPresent());
                MetalInterop.set(depth,"clear_stencil",(byte)0);MetalInterop.set(depth,"mip_level",view.baseMipLevel());MetalInterop.set(depth,"layer",(byte)0);
            }
            // Use LWJGL's typed Java overload directly. It derives num_color_targets from
            // SDL_GPUColorTargetInfo.Buffer; passing an explicit count belongs only to the
            // raw nSDL_BeginGPURenderPass C-shaped entry point.
            long pass=SDLGPU.SDL_BeginGPURenderPass(commandHandle(),colors,depth);
            if(pass==0L)throw new IllegalStateException("SDL_BeginGPURenderPass failed: "+MetalInterop.lastSdlError());
            int outputWidth=0, outputHeight=0;
            for (RenderPassDescriptor.Attachment<java.util.Optional<Vector4fc>> attachment : d.colorAttachments()) {
                if (attachment != null) {
                    outputWidth = attachment.textureView().getWidth(0);
                    outputHeight = attachment.textureView().getHeight(0);
                    break;
                }
            }
            if (outputWidth == 0 && d.depthAttachment() != null) {
                outputWidth = d.depthAttachment().textureView().getWidth(0);
                outputHeight = d.depthAttachment().textureView().getHeight(0);
            }
            activePass=new MetalRenderPass(this,pass,d.renderArea(),outputWidth,outputHeight,d.depthAttachment()!=null,targetMode); return activePass;
        } finally { MetalInterop.free(depth);MetalInterop.free(colors); MetalPerfCounters.renderPass(perfStart); }
    }
    @Override public void submitRenderPass(){if(activePass==null)throw new IllegalStateException("No Metal render pass");MetalRenderPass ending=activePass; ending.traceEnd(); SDLGPU.SDL_EndGPURenderPass(ending.handle());activePass=null;MetalTrace.log("PASS_NATIVE_END", "pass=" + ending.tracePassId());}

    @Override public void clearColorTexture(GpuTexture texture,Vector4fc value){ clear(texture,value,null,1.0,0,0,texture.getWidth(0),texture.getHeight(0),0); }
    @Override public void clearColorAndDepthTextures(GpuTexture color,Vector4fc value,GpuTexture depth,double depthValue){ clear(color,value,depth,depthValue,0,0,color.getWidth(0),color.getHeight(0),0); }
    @Override public void clearColorAndDepthTextures(GpuTexture color,Vector4fc value,GpuTexture depth,double depthValue,int x,int y,int width,int height,int mip){
        if (x == 0 && y == 0 && width == color.getWidth(mip) && height == color.getHeight(mip)) {
            clear(color,value,depth,depthValue,x,y,width,height,mip);
            return;
        }
        // GuiItemAtlas recycles individual slots with this regional overload. SDL's attachment
        // LOADOP_CLEAR clears the whole texture, not RenderPearl's requested rectangle, so the
        // correctness path uploads exactly the requested color/depth rectangle instead. Keep a
        // diagnostic switch so the Metal GUI path can be A/B tested without rebuilding.
        if (device.guiRegionalClearUpload()) {
            clearColorDepthRegionByUpload(color,value,depth,depthValue,x,y,width,height,mip);
        } else {
            clear(color,value,depth,depthValue,x,y,width,height,mip);
        }
    }
    @Override public void clearDepthTexture(GpuTexture depth,double value){ clear(null,null,depth,value,0,0,depth.getWidth(0),depth.getHeight(0),0); }
    private void clear(GpuTexture color,Vector4fc colorValue,GpuTexture depth,double depthValue,int x,int y,int w,int h,int mip){
        var b=RenderPassDescriptor.builder(()->"Metal clear").withRenderArea(new com.mojang.renderpearl.api.commands.RenderPass.RenderArea(x,y,w,h));
        MetalTextureView cv=null,dv=null; if(color!=null){cv=new MetalTextureView(color,mip,1);b.withColorAttachment(cv,java.util.Optional.of(colorValue));} if(depth!=null){dv=new MetalTextureView(depth,mip,1);b.withDepthAttachment(dv,java.util.OptionalDouble.of(depthValue));}
        createRenderPass(b.build());submitRenderPass();if(cv!=null)cv.close();if(dv!=null)dv.close();
    }

    private void clearColorDepthRegionByUpload(GpuTexture color, Vector4fc colorValue,
                                               GpuTexture depth, double depthValue,
                                               int x, int y, int width, int height, int mip) {
        if (activePass != null) throw new IllegalStateException("Cannot clear texture region inside a Metal render pass");
        if (width <= 0 || height <= 0) return;
        if (REGIONAL_CLEAR_MARKER_REPORTED.compareAndSet(false, true)) {
            System.out.println("[Native Accelerator] Metal regional clear marker: subresource-upload-fix30");
        }
        if (color == null || depth == null) {
            throw new UnsupportedOperationException("Metal regional clear currently requires color + depth together");
        }
        if (color.getFormat() != GpuFormat.RGBA8_UNORM || depth.getFormat() != GpuFormat.D32_FLOAT) {
            throw new UnsupportedOperationException(
                    "Metal regional clear currently supports RGBA8_UNORM + D32_FLOAT (got "
                            + color.getFormat() + " + " + depth.getFormat() + ")");
        }

        int pixels = Math.multiplyExact(width, height);
        ByteBuffer colorBytes = ByteBuffer.allocateDirect(Math.multiplyExact(pixels, 4));
        byte r = unorm8(colorValue.x());
        byte g = unorm8(colorValue.y());
        byte b = unorm8(colorValue.z());
        byte a = unorm8(colorValue.w());
        for (int i = 0; i < pixels; i++) colorBytes.put(r).put(g).put(b).put(a);
        colorBytes.flip();

        ByteBuffer depthBytes = ByteBuffer.allocateDirect(Math.multiplyExact(pixels, Float.BYTES))
                .order(ByteOrder.nativeOrder());
        float z = (float)depthValue;
        for (int i = 0; i < pixels; i++) depthBytes.putFloat(z);
        depthBytes.flip();

        writeToTexture(color, colorBytes, mip, 0, x, y, width, height);
        if (device.mac1Compat() && device.mac1RegionalDepthAttachmentClear()) {
            // Legacy Mac1/NVIDIA: avoid uploading D32_FLOAT subregions through a transfer buffer.
            // The GUI item atlas only needs fresh depth while rendering the next slot; previous
            // slots' depth values are not sampled after their color result has been produced.
            // Clearing the complete depth attachment is therefore conservative and avoids the
            // unreliable D32 transfer path without erasing neighboring color slots.
            MetalTrace.log("GUI_ATLAS_DEPTH_SAFE_CLEAR", "depth=" + MetalTrace.hex(metal(depth).handle())
                    + " requestedRect=" + x + "," + y + "," + width + "x" + height + " value=" + depthValue);
            clearDepthTexture(depth, depthValue);
        } else {
            writeToTexture(depth, depthBytes, mip, 0, x, y, width, height);
        }
    }

    private static byte unorm8(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return (byte)Math.round(clamped * 255.0f);
    }

    @Override public void writeToBuffer(GpuBufferSlice dst,ByteBuffer data){
        if(activePass!=null)throw new IllegalStateException("Cannot write buffer inside a Metal render pass");
        MetalTrace.log("WRITE_BUFFER", "buffer=" + MetalTrace.hex(metal(dst.buffer()).handle()) + " offset=" + dst.offset() + " bytes=" + data.remaining());
        MetalGpuBuffer b=metal(dst.buffer()); b.overwriteShadow(dst.offset(),data); b.markDirty(dst.offset(),data.remaining());
    }
    @Override public void copyToBuffer(GpuBufferSlice src,GpuBufferSlice dst){
        if(activePass!=null)throw new IllegalStateException("Cannot copy buffer inside a Metal render pass");
        if(src.length()>dst.length())throw new IllegalArgumentException("Destination slice too small");
        device.flushDirtyBuffers(commandHandle());
        MetalGpuBuffer source=metal(src.buffer()), destination=metal(dst.buffer());
        // Keep the CPU shadow coherent for later maps/uniform pushes while the actual copy stays GPU-side.
        ByteBuffer bytes=source.shadowSlice(src.offset(),src.length()); destination.overwriteShadow(dst.offset(),bytes);
        MetalTransfers.encodeCopyBuffer(commandHandle(),source,src.offset(),destination,dst.offset(),src.length());
    }
    @Override public void writeToTexture(GpuTexture texture,ByteBuffer data,int mip,int depthOrLayer,int destX,int destY,int width,int height){
        if(activePass!=null)throw new IllegalStateException("Cannot upload texture inside a Metal render pass");
        MetalTrace.log("WRITE_TEXTURE", "texture=" + MetalTrace.hex(metal(texture).handle()) + " mip=" + mip + " layer=" + depthOrLayer + " rect=" + destX + "," + destY + "," + width + "x" + height + " bytes=" + data.remaining());
        MetalGpuTexture destination=metal(texture);
        if(depthOrLayer<0 || depthOrLayer>=destination.getDepthOrLayers())
            throw new IllegalArgumentException("Texture layer out of range: "+depthOrLayer+" / "+destination.getDepthOrLayers());
        MetalPerfCounters.textureUpload(Math.multiplyExact(Math.multiplyExact(width,height), destination.getFormat().blockSize()));
        long retained = MetalTransfers.encodeUploadTexture(device.handle(),commandHandle(),destination,data,mip,depthOrLayer,destX,destY,width,height,
                device.mac1Compat() && device.mac1RetainTextureTransfers());
        if (retained != 0L) retainedTextureTransfers.add(retained);
    }
    @Override public void copyBufferToTexture(GpuBufferSlice source,int sourceX,int sourceY,int sourceWidth,int sourceHeight,GpuTexture destination,int destX,int destY,int copyWidth,int copyHeight,int mip,int layer){
        MetalGpuTexture target=metal(destination);
        if(layer<0 || layer>=target.getDepthOrLayers())
            throw new IllegalArgumentException("Texture layer out of range: "+layer+" / "+target.getDepthOrLayers());
        int bpp=destination.getFormat().blockSize();ByteBuffer src=metal(source.buffer()).shadowSlice(source.offset(),source.length());ByteBuffer packed=ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(copyWidth,copyHeight),bpp));
        for(int row=0;row<copyHeight;row++){int pos=((sourceY+row)*sourceWidth+sourceX)*bpp;ByteBuffer r=src.duplicate();r.position(pos).limit(pos+copyWidth*bpp);packed.put(r);}packed.flip();
        MetalPerfCounters.textureUpload(packed.remaining());
        long retained = MetalTransfers.encodeUploadTexture(device.handle(),commandHandle(),target,packed,mip,layer,destX,destY,copyWidth,copyHeight,
                device.mac1Compat() && device.mac1RetainTextureTransfers());
        if (retained != 0L) retainedTextureTransfers.add(retained);
    }
    @Override public void copyTextureToBuffer(GpuTexture source,GpuBuffer destination,long offset,Runnable callback,int mip){copyTextureToBuffer(source,destination,offset,callback,mip,0,0,source.getWidth(mip),source.getHeight(mip));}
    @Override public void copyTextureToBuffer(GpuTexture source,GpuBuffer destination,long offset,Runnable callback,int mip,int x,int y,int width,int height){
        if(activePass!=null)throw new IllegalStateException("Cannot read back texture inside a Metal render pass");
        // Readback is intentionally the rare synchronous path. Submit earlier frame work first so the
        // downloaded texture observes all preceding commands.
        submit();
        ByteBuffer bytes=MetalTransfers.downloadTexture(device.handle(),metal(source),mip,x,y,width,height);
        MetalGpuBuffer dst=metal(destination);dst.overwriteShadow(offset,bytes);dst.markDirty(offset,bytes.remaining());callback.run();
    }
    @Override public void copyTextureToTexture(GpuTexture src,GpuTexture dst,int mip,int destX,int destY,int sourceX,int sourceY,int width,int height){
        if(activePass!=null)throw new IllegalStateException("Cannot copy texture inside a Metal render pass");
        MetalTrace.log("COPY_TEXTURE", "src=" + MetalTrace.hex(metal(src).handle()) + " dst=" + MetalTrace.hex(metal(dst).handle()) + " mip=" + mip + " srcXY=" + sourceX + "," + sourceY + " dstXY=" + destX + "," + destY + " size=" + width + "x" + height);
        MetalTransfers.encodeCopyTexture(commandHandle(),metal(src),metal(dst),mip,destX,destY,sourceX,sourceY,width,height);
    }
    @Override public GpuFence createFence(){
        if(activePass!=null) throw new IllegalStateException("Cannot create Metal fence inside a render pass");
        if (device.mac1Compat()) {
            // Vulkan createFence() only captures the current submit index; it does NOT submit.
            // The old Metal implementation submitted here, splitting one Minecraft frame into
            // many tiny command buffers (the fix49 trace showed dozens per frame).
            long submitIndex = logicalCurrentSubmitIndex;
            MetalTrace.log("LOGICAL_FENCE_CREATE", "encoder=" + traceEncoderId + " submitIndex=" + submitIndex
                    + " cmd=" + MetalTrace.hex(command));
            return new MetalFence(this, submitIndex);
        }

        // Keep the existing native-fence behavior for non-Mac1 devices for now; the parity change
        // is deliberately isolated to the legacy NVIDIA/OCLP path under investigation.
        long cmd=commandHandle();
        MetalTrace.log("FENCE_SUBMIT_BEGIN", "encoder=" + traceEncoderId + " seq=" + traceCommandSequence + " cmd=" + MetalTrace.hex(cmd) + " dirtyBuffers=" + device.hasDirtyBuffers() + " transientEmpty=" + transientMemory.isEmpty());
        if(device.hasDirtyBuffers()) device.flushDirtyBuffers(cmd);
        long start=MetalPerfCounters.tic();
        long fence=SDLGPU.SDL_SubmitGPUCommandBufferAndAcquireFence(cmd);
        long submitNs=MetalPerfCounters.tic()-start;
        MetalPerfCounters.submit(start);
        if(fence==0L) {
            MetalTrace.log("FENCE_SUBMIT_FAIL", "encoder=" + traceEncoderId + " seq=" + traceCommandSequence + " cmd=" + MetalTrace.hex(cmd) + " error=\"" + MetalTrace.safe(MetalInterop.lastSdlError()) + "\"");
            throw new IllegalStateException("SDL_SubmitGPUCommandBufferAndAcquireFence failed: "+MetalInterop.lastSdlError());
        }
        MetalTrace.log("FENCE_SUBMITTED", "encoder=" + traceEncoderId + " seq=" + traceCommandSequence + " cmd=" + MetalTrace.hex(cmd) + " fence=" + MetalTrace.hex(fence) + " submitNs=" + submitNs);
        command=0L;
        if (!transientMemory.isEmpty()) {
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.PointerBuffer fences = stack.mallocPointer(1).put(0, fence);
                long waitStart = System.nanoTime();
                MetalTrace.log("FENCE_WAIT_BEGIN", "encoder=" + traceEncoderId + " fence=" + MetalTrace.hex(fence) + " reason=transient-retire");
                boolean waitOk = SDLGPU.SDL_WaitForGPUFences(device.handle(), true, fences);
                long waitNs = System.nanoTime() - waitStart;
                MetalTrace.log("FENCE_WAIT_END", "encoder=" + traceEncoderId + " fence=" + MetalTrace.hex(fence) + " ok=" + waitOk + " ns=" + waitNs + " reason=transient-retire");
                if (!waitOk) throw new IllegalStateException("SDL_WaitForGPUFences failed while retiring transient memory: " + MetalInterop.lastSdlError());
            }
        }
        transientMemory.release();
        transientMemory=new MetalTransientMemory(device);
        MetalTrace.log("FENCE_RETURN", "encoder=" + traceEncoderId + " fence=" + MetalTrace.hex(fence));
        return new MetalFence(device,fence);
    }

    synchronized boolean awaitLogicalFence(long submitIndex, long timeoutNs) {
        if (logicalCompletedSubmitIndex >= submitIndex) {
            MetalTrace.log("LOGICAL_FENCE_COMPLETE", "encoder=" + traceEncoderId + " submitIndex=" + submitIndex
                    + " completedSubmit=" + logicalCompletedSubmitIndex);
            return true;
        }
        if (submitIndex >= logicalCurrentSubmitIndex) {
            MetalTrace.log("LOGICAL_FENCE_PENDING_CURRENT", "encoder=" + traceEncoderId + " submitIndex=" + submitIndex
                    + " currentSubmit=" + logicalCurrentSubmitIndex + " timeoutNs=" + timeoutNs);
            if (timeoutNs == 0L) return false;
            throw new IllegalStateException("Cannot wait on a fence for the current unsubmitted Metal submit");
        }
        return logicalCompletedSubmitIndex >= submitIndex;
    }

    @Override public void writeTimestamp(GpuQueryPool pool,int index){if(pool instanceof MetalQueryPool p)p.write(index);}

    /**
     * Present the complete current framebuffer to the exact swapchain image acquired for this
     * command buffer. Legacy Mac1/OCLP NVIDIA uses a strict copy when dimensions match, avoiding
     * the optimized blitter and any destination load semantics.
     */
    void presentToSwapchain(MetalTextureView source,long swapchain,int width,int height,long frameSequence){
        device.notePresentedSourceHandle(source.metalTexture().handle());
        MetalTrace.log("PRESENT_ENCODE", "frame=" + frameSequence + " source=" + MetalTrace.hex(source.metalTexture().handle()) + " sourceSize=" + source.getWidth(0) + "x" + source.getHeight(0) + " swap=" + MetalTrace.hex(swapchain) + " swapSize=" + width + "x" + height);
        int sourceWidth=source.getWidth(0),sourceHeight=source.getHeight(0);
        boolean strict=device.strictSwapchainPresent() && sourceWidth==width && sourceHeight==height;
        if(strict){
            MetalTransfers.encodeCopyTextureToRawHandle(commandHandle(),source.metalTexture(),source.baseMipLevel(),swapchain,width,height);
        } else {
            if(device.strictSwapchainPresent() && (sourceWidth!=width || sourceHeight!=height) && (frameSequence<=8 || frameSequence%120==0)){
                MetalTrace.log("PRESENT_STRICT_FALLBACK", "frame=" + frameSequence + " source=" + sourceWidth + "x" + sourceHeight + " swapchain=" + width + "x" + height);
            }
            blitToSwapchain(source,swapchain,width,height);
        }
        if(frameSequence<=16 || frameSequence%120==0){
            MetalTrace.log("PRESENT_PERIODIC", "frame=" + frameSequence + " mode=" + (strict?"strict-copy":"blit")
                    + " source=" + MetalTrace.hex(source.metalTexture().handle()) + " swapchain=" + MetalTrace.hex(swapchain)
                    + " size=" + width + "x" + height);
        }
    }

    private void blitToSwapchain(MetalTextureView source,long swapchain,int width,int height){
        Object info=MetalInterop.calloc("SDL_GPUBlitInfo");
        try{Object s=MetalInterop.get(info,"source"),d=MetalInterop.get(info,"destination");setBlitRegion(s,source.metalTexture().handle(),source.baseMipLevel(),source.getWidth(0),source.getHeight(0));setBlitRegion(d,swapchain,0,width,height);MetalInterop.set(info,"load_op",MetalInterop.sdl("SDL_GPU_LOADOP_DONT_CARE"));MetalInterop.set(info,"flip_mode",0);MetalInterop.set(info,"filter",MetalInterop.sdl("SDL_GPU_FILTER_LINEAR"));MetalInterop.set(info,"cycle",false);MetalInterop.sdlCall("SDL_BlitGPUTexture",commandHandle(),info);}finally{MetalInterop.free(info);}
    }
    private static void setBlitRegion(Object r,long texture,int mip,int w,int h){MetalInterop.set(r,"texture",texture);MetalInterop.set(r,"mip_level",mip);MetalInterop.set(r,"layer_or_depth_plane",0);MetalInterop.set(r,"x",0);MetalInterop.set(r,"y",0);MetalInterop.set(r,"w",w);MetalInterop.set(r,"h",h);}
    private static void setColor(Object c,Vector4fc v){MetalInterop.set(c,"r",v.x());MetalInterop.set(c,"g",v.y());MetalInterop.set(c,"b",v.z());MetalInterop.set(c,"a",v.w());}
    private static MetalGpuTexture metal(GpuTexture t){if(!(t instanceof MetalGpuTexture m))throw new IllegalArgumentException("Foreign texture in Metal backend");return m;}
    private static MetalGpuBuffer metal(GpuBuffer b){if(!(b instanceof MetalGpuBuffer m))throw new IllegalArgumentException("Foreign buffer in Metal backend");return m;}
}
