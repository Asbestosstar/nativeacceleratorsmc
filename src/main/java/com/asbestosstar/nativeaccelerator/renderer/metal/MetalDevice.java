package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.api.*;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLGPU;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class MetalDevice implements GpuDeviceBackend {
    private final long handle;
    private final GpuDebugOptions debug;
    private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
    private final DeviceInfo info;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<MetalGpuBuffer> dirtyBuffers = ConcurrentHashMap.newKeySet();
    private final boolean indirectDrawSupported;

    MetalDevice(long handle,GpuDebugOptions debug, MetalBackend.MetalCapabilityTier capabilityTier) {
        this.handle=handle;this.debug=debug;
        // SDL documents that the MacFamily1 compatibility path does not support Metal graphics
        // indirect command buffers. RenderPearl must see this as a hard capability boundary or it
        // will select the terrain MDI path and SDL/Metal will abort in validation.
        this.indirectDrawSupported = capabilityTier == MetalBackend.MetalCapabilityTier.MAC2_OR_NEWER;
        String driver;
        try { Object d=MetalInterop.sdlCall("SDL_GetGPUDeviceDriver",handle);driver=String.valueOf(d); } catch(Throwable t){driver="metal";}
        int maxIndirectDrawCount = indirectDrawSupported ? 1_048_576 : 0;
        DeviceLimits limits=new DeviceLimits(16,1,16384,Integer.MAX_VALUE,1,4,maxIndirectDrawCount);
        DeviceFeatures features=new DeviceFeatures(
                true,  // wireframeFillMode
                false, // shaderDrawParameters
                false, // multiDrawDirectInterleaved
                false, // multiDrawDirectSeparate
                indirectDrawSupported,
                indirectDrawSupported,
                true,  // nonZeroFirstInstance for direct draws
                false  // persistentMapping
        );
        HintsAndWorkarounds hints=new HintsAndWorkarounds(true,false,true,!indirectDrawSupported);
        this.info=new DeviceInfo("SDL3 Metal GPU","Apple / Metal",driver,true,"Metal (SDL3)",1.0f,limits,features,Set.of("SDL_GPU","Metal"),hints,DeviceType.OTHER);
        int framesInFlight = Math.max(1, Math.min(3, Integer.getInteger(
                "nativeaccelerator.renderer.metal.framesInFlight", 3)));
        try { SDLGPU.SDL_SetGPUAllowedFramesInFlight(handle,framesInFlight); } catch(Throwable ignored){}
    }


    boolean indirectDrawSupported() { return indirectDrawSupported; }

    void markDirty(MetalGpuBuffer buffer) {
        if (!closed.get()) dirtyBuffers.add(buffer);
    }

    void forgetDirty(MetalGpuBuffer buffer) {
        dirtyBuffers.remove(buffer);
    }

    boolean hasDirtyBuffers() {
        return !dirtyBuffers.isEmpty();
    }

    /**
     * Coalesce all CPU-side mapped writes into one GPU copy pass on the frame command buffer.
     * Uniform-only buffers are never registered here: they are pushed directly from their CPU shadow.
     */
    void flushDirtyBuffers(long commandBuffer) {
        if (dirtyBuffers.isEmpty()) return;
        long copyPass = SDLGPU.SDL_BeginGPUCopyPass(commandBuffer);
        if (copyPass == 0L) throw new IllegalStateException("SDL_BeginGPUCopyPass failed: " + MetalInterop.lastSdlError());
        try {
            List<MetalTransfers.BufferUpload> uploads = new ArrayList<>();
            for (MetalGpuBuffer buffer : List.copyOf(dirtyBuffers)) {
                MetalGpuBuffer.DirtyRange range = buffer.drainDirtyRange();
                if (range == null) {
                    dirtyBuffers.remove(buffer);
                    continue;
                }
                uploads.add(new MetalTransfers.BufferUpload(buffer.handle(), range.offset(), range.bytes()));
                if (!buffer.hasDirtyRange()) dirtyBuffers.remove(buffer);
            }
            MetalTransfers.encodeUploadBuffersInPass(handle(), copyPass, uploads);
        } finally {
            SDLGPU.SDL_EndGPUCopyPass(copyPass);
        }
    }
    long handle(){if(closed.get())throw new IllegalStateException("Metal device is closed");return handle;}
    @Override public GpuSurfaceBackend createSurface(long window,BooleanSupplier iconified){return new MetalSurface(this,window,iconified);}
    @Override public CommandEncoderBackend createCommandEncoder(){return new MetalCommandEncoder(this);}
    @Override public GpuSampler createSampler(AddressMode u,AddressMode v,FilterMode min,FilterMode mag,int maxAnisotropy,OptionalDouble maxLod){return new MetalSampler(this,u,v,min,mag,maxAnisotropy,maxLod);}
    @Override public GpuTexture createTexture(@Nullable String label,int usage,GpuFormat format,int width,int height,int depthOrLayers,int mipLevels){return new MetalGpuTexture(this,label,usage,format,width,height,depthOrLayers,mipLevels);}
    @Override public GpuTextureView createTextureView(GpuTexture texture,int baseMip,int mipLevels){return new MetalTextureView(texture,baseMip,mipLevels);}
    @Override public GpuBuffer createBuffer(@Nullable Supplier<String> label,int usage,long size){MetalGpuBuffer b=new MetalGpuBuffer(this,usage,size);nameBuffer(label,b);return b;}
    @Override public GpuBuffer createBuffer(@Nullable Supplier<String> label,int usage,ByteBuffer data){MetalGpuBuffer b=new MetalGpuBuffer(this,usage,data);nameBuffer(label,b);return b;}
    private void nameBuffer(@Nullable Supplier<String> label,MetalGpuBuffer b){if(label==null)return;try{String name=label.get();if(name!=null&&!name.isBlank())MetalInterop.sdlCall("SDL_SetGPUBufferName",handle(),b.handle(),name);}catch(Throwable ignored){}}
    @Override public List<String> getLastDebugMessages(){synchronized(messages){return List.copyOf(messages);}}
    @Override public boolean isDebuggingEnabled(){return debug.logLevel()>0||debug.useLabels()||debug.useValidationLayers();}
    @Override public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo createInfo){
        return ()->{try{return new MetalRenderPipeline(this,createInfo);}catch(Throwable t){messages.add("Metal pipeline '"+createInfo.name()+"' failed: "+t);return null;}};
    }
    @Override public GpuQueryPool createTimestampQueryPool(int size){return new MetalQueryPool(size);}
    @Override public long getTimestampCalibrationOffset(){return 0L;}
    @Override public DeviceInfo getDeviceInfo(){return info;}
    @Override public void close(){if(closed.compareAndSet(false,true)){try{SDLGPU.SDL_WaitForGPUIdle(handle);}catch(Throwable ignored){}SDLGPU.SDL_DestroyGPUDevice(handle);MetalBackend.deviceClosed();}}
}
