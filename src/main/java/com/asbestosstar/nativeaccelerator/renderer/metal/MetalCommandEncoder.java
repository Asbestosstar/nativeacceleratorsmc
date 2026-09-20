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

final class MetalCommandEncoder implements CommandEncoderBackend {
    private static final AtomicBoolean REGIONAL_CLEAR_MARKER_REPORTED = new AtomicBoolean();
    private final MetalDevice device;
    private long command;
    private MetalRenderPass activePass;
    private MetalTransientMemory transientMemory;

    MetalCommandEncoder(MetalDevice device) { this.device=device; this.transientMemory=new MetalTransientMemory(device); }
    MetalDevice device() { return device; }
    long commandHandle() {
        if(command==0L){ command=SDLGPU.SDL_AcquireGPUCommandBuffer(device.handle()); if(command==0L)throw new IllegalStateException("SDL_AcquireGPUCommandBuffer failed: "+MetalInterop.lastSdlError()); }
        return command;
    }

    @Override public void submit() {
        if(activePass!=null) throw new IllegalStateException("Cannot submit Metal command buffer inside a render pass");
        if(device.hasDirtyBuffers()) device.flushDirtyBuffers(commandHandle());
        if(command!=0L){
            long start=MetalPerfCounters.tic();
            if (transientMemory.isEmpty()) {
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
            command=0L;
            transientMemory=new MetalTransientMemory(device);
        } else {
            transientMemory.release();
            transientMemory=new MetalTransientMemory(device);
        }
        device.reapCompletedTransientMemory();
    }
    @Override public TransientMemory transientMemory(){return transientMemory;}

    @Override public RenderPassBackend createRenderPass(RenderPassDescriptor d) {
        long perfStart=MetalPerfCounters.tic();
        if(activePass!=null)throw new IllegalStateException("Nested Metal render pass");
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
                    Object out=MetalInterop.get(colors,i); MetalInterop.set(out,"texture",view.metalTexture().handle()); MetalInterop.set(out,"mip_level",view.baseMipLevel());MetalInterop.set(out,"layer_or_depth_plane",0);
                    MetalInterop.set(out,"load_op",MetalInterop.sdl(a.clearValue().isPresent()?"SDL_GPU_LOADOP_CLEAR":"SDL_GPU_LOADOP_LOAD"));
                    MetalInterop.set(out,"store_op",MetalInterop.sdl("SDL_GPU_STOREOP_STORE"));
                    // SDL resource cycling is safe when the attachment is fully cleared: no prior
                    // contents need to survive. This avoids reusing a physical render target that a
                    // previous in-flight frame may still be presenting on legacy MacFamily1 drivers.
                    MetalInterop.set(out,"cycle",device.cycleClearedTargets() && a.clearValue().isPresent());
                    if(a.clearValue().isPresent())setColor(MetalInterop.get(out,"clear_color"),a.clearValue().get());
                }
            }
            if(d.depthAttachment()!=null){
                var a=d.depthAttachment(); if(!(a.textureView() instanceof MetalTextureView view))throw new IllegalArgumentException("Foreign depth attachment");
                depth=(SDL_GPUDepthStencilTargetInfo)MetalInterop.calloc("SDL_GPUDepthStencilTargetInfo"); MetalInterop.set(depth,"texture",view.metalTexture().handle());
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
            activePass=new MetalRenderPass(this,pass,d.renderArea()); return activePass;
        } finally { MetalInterop.free(depth);MetalInterop.free(colors); MetalPerfCounters.renderPass(perfStart); }
    }
    @Override public void submitRenderPass(){if(activePass==null)throw new IllegalStateException("No Metal render pass");SDLGPU.SDL_EndGPURenderPass(activePass.handle());activePass=null;}

    @Override public void clearColorTexture(GpuTexture texture,Vector4fc value){ clear(texture,value,null,1.0,0,0,texture.getWidth(0),texture.getHeight(0),0); }
    @Override public void clearColorAndDepthTextures(GpuTexture color,Vector4fc value,GpuTexture depth,double depthValue){ clear(color,value,depth,depthValue,0,0,color.getWidth(0),color.getHeight(0),0); }
    @Override public void clearColorAndDepthTextures(GpuTexture color,Vector4fc value,GpuTexture depth,double depthValue,int x,int y,int width,int height,int mip){
        if (x == 0 && y == 0 && width == color.getWidth(mip) && height == color.getHeight(mip)) {
            clear(color,value,depth,depthValue,x,y,width,height,mip);
            return;
        }
        clearColorDepthRegionByUpload(color,value,depth,depthValue,x,y,width,height,mip);
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
        writeToTexture(depth, depthBytes, mip, 0, x, y, width, height);
    }

    private static byte unorm8(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return (byte)Math.round(clamped * 255.0f);
    }

    @Override public void writeToBuffer(GpuBufferSlice dst,ByteBuffer data){
        if(activePass!=null)throw new IllegalStateException("Cannot write buffer inside a Metal render pass");
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
        MetalGpuTexture destination=metal(texture);
        if(depthOrLayer<0 || depthOrLayer>=destination.getDepthOrLayers())
            throw new IllegalArgumentException("Texture layer out of range: "+depthOrLayer+" / "+destination.getDepthOrLayers());
        MetalPerfCounters.textureUpload(Math.multiplyExact(Math.multiplyExact(width,height), destination.getFormat().blockSize()));
        MetalTransfers.encodeUploadTexture(device.handle(),commandHandle(),destination,data,mip,depthOrLayer,destX,destY,width,height);
    }
    @Override public void copyBufferToTexture(GpuBufferSlice source,int sourceX,int sourceY,int sourceWidth,int sourceHeight,GpuTexture destination,int destX,int destY,int copyWidth,int copyHeight,int mip,int layer){
        MetalGpuTexture target=metal(destination);
        if(layer<0 || layer>=target.getDepthOrLayers())
            throw new IllegalArgumentException("Texture layer out of range: "+layer+" / "+target.getDepthOrLayers());
        int bpp=destination.getFormat().blockSize();ByteBuffer src=metal(source.buffer()).shadowSlice(source.offset(),source.length());ByteBuffer packed=ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(copyWidth,copyHeight),bpp));
        for(int row=0;row<copyHeight;row++){int pos=((sourceY+row)*sourceWidth+sourceX)*bpp;ByteBuffer r=src.duplicate();r.position(pos).limit(pos+copyWidth*bpp);packed.put(r);}packed.flip();
        MetalPerfCounters.textureUpload(packed.remaining());
        MetalTransfers.encodeUploadTexture(device.handle(),commandHandle(),target,packed,mip,layer,destX,destY,copyWidth,copyHeight);
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
        MetalTransfers.encodeCopyTexture(commandHandle(),metal(src),metal(dst),mip,destX,destY,sourceX,sourceY,width,height);
    }
    @Override public GpuFence createFence(){
        if(activePass!=null) throw new IllegalStateException("Cannot create Metal fence inside a render pass");
        // A fence is an ordering marker, not a device-idle operation. Submit an empty command buffer
        // when needed so the returned fence is ordered after all prior submissions.
        long cmd=commandHandle();
        if(device.hasDirtyBuffers()) device.flushDirtyBuffers(cmd);
        long start=MetalPerfCounters.tic();
        long fence=SDLGPU.SDL_SubmitGPUCommandBufferAndAcquireFence(cmd);
        MetalPerfCounters.submit(start);
        if(fence==0L) throw new IllegalStateException("SDL_SubmitGPUCommandBufferAndAcquireFence failed: "+MetalInterop.lastSdlError());
        command=0L;
        // The returned fence is owned by MetalFence, so it cannot also be owned by the device's
        // retirement queue. Keep transient resources alive until this submission completes.
        if (!transientMemory.isEmpty()) {
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.PointerBuffer fences = stack.mallocPointer(1).put(0, fence);
                if (!SDLGPU.SDL_WaitForGPUFences(device.handle(), true, fences)) {
                    throw new IllegalStateException("SDL_WaitForGPUFences failed while retiring transient memory: " + MetalInterop.lastSdlError());
                }
            }
        }
        transientMemory.release();
        transientMemory=new MetalTransientMemory(device);
        return new MetalFence(device,fence);
    }
    @Override public void writeTimestamp(GpuQueryPool pool,int index){if(pool instanceof MetalQueryPool p)p.write(index);}

    void blitToSwapchain(MetalTextureView source,long swapchain,int width,int height){
        Object info=MetalInterop.calloc("SDL_GPUBlitInfo");
        try{Object s=MetalInterop.get(info,"source"),d=MetalInterop.get(info,"destination");setBlitRegion(s,source.metalTexture().handle(),source.baseMipLevel(),source.getWidth(0),source.getHeight(0));setBlitRegion(d,swapchain,0,width,height);MetalInterop.set(info,"load_op",MetalInterop.sdl("SDL_GPU_LOADOP_DONT_CARE"));MetalInterop.set(info,"flip_mode",0);MetalInterop.set(info,"filter",MetalInterop.sdl("SDL_GPU_FILTER_LINEAR"));MetalInterop.set(info,"cycle",false);MetalInterop.sdlCall("SDL_BlitGPUTexture",commandHandle(),info);}finally{MetalInterop.free(info);}
    }
    private static void setBlitRegion(Object r,long texture,int mip,int w,int h){MetalInterop.set(r,"texture",texture);MetalInterop.set(r,"mip_level",mip);MetalInterop.set(r,"layer_or_depth_plane",0);MetalInterop.set(r,"x",0);MetalInterop.set(r,"y",0);MetalInterop.set(r,"w",w);MetalInterop.set(r,"h",h);}
    private static void setColor(Object c,Vector4fc v){MetalInterop.set(c,"r",v.x());MetalInterop.set(c,"g",v.y());MetalInterop.set(c,"b",v.z());MetalInterop.set(c,"a",v.w());}
    private static MetalGpuTexture metal(GpuTexture t){if(!(t instanceof MetalGpuTexture m))throw new IllegalArgumentException("Foreign texture in Metal backend");return m;}
    private static MetalGpuBuffer metal(GpuBuffer b){if(!(b instanceof MetalGpuBuffer m))throw new IllegalArgumentException("Foreign buffer in Metal backend");return m;}
}
