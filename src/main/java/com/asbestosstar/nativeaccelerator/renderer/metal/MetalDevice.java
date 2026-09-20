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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;
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
    private final MetalRuntimeCapabilities runtime;
    private final MetalFanIndexBuffer fanIndexBuffer;
    private final Deque<RetiredTransientMemory> retiredTransientMemory = new ArrayDeque<>();
    private final int framesInFlight;
    private final boolean mac1Compat;

    MetalDevice(long handle, GpuDebugOptions debug, MetalBackend.MetalCapabilityTier capabilityTier,
                MacMetalCapabilities.Result metal) {
        this.handle=handle;this.debug=debug;
        this.mac1Compat = capabilityTier == MetalBackend.MetalCapabilityTier.MAC1_COMPAT;
        this.runtime = MetalRuntimeCapabilities.probe(handle, capabilityTier, metal);
        this.fanIndexBuffer = new MetalFanIndexBuffer(this);
        String driver;
        try { Object d=MetalInterop.sdlCall("SDL_GetGPUDeviceDriver",handle);driver=String.valueOf(d); } catch(Throwable t){driver="metal";}
        // RenderPearl sees logical indirect support on every Metal profile. On GPUs where native
        // graphics ICBs are unavailable or fail verification, MetalRenderPass decodes the standard
        // 16/20-byte indirect argument structures from the CPU shadow and emits equivalent direct
        // draws. This keeps Minecraft on its efficient instanced terrain preparation path even on
        // MacFamily1 and patched/spoofed stacks.
        int maxIndirectDrawCount = 1_048_576;

        // Intel-era macOS Metal requires conservative constant-buffer alignment. Apple Silicon uses
        // much tighter alignment, but 16 still keeps RenderPearl allocations naturally vector-aligned.
        int minUniformAlignment = capabilityTier == MetalBackend.MetalCapabilityTier.APPLE_SILICON ? 16 : 256;
        DeviceLimits limits=new DeviceLimits(runtime.maxAnisotropy(),minUniformAlignment,16384,Integer.MAX_VALUE,1,4,maxIndirectDrawCount);
        DeviceFeatures features=new DeviceFeatures(
                true,  // wireframeFillMode
                false, // shaderDrawParameters
                false, // multiDrawDirectInterleaved
                false, // multiDrawDirectSeparate
                true,  // logical multiDrawIndirect; native or CPU-emulated
                true,  // logical drawIndirect; native or CPU-emulated
                true,  // nonZeroFirstInstance for direct draws
                false  // persistentMapping
        );
        HintsAndWorkarounds hints=new HintsAndWorkarounds(true,false,true,false);

        String deviceName = !runtime.actualDeviceName().isBlank()
                ? runtime.actualDeviceName()
                : (metal.available() && !metal.deviceName().isBlank() ? metal.deviceName() : "SDL3 Metal GPU");
        String vendor = vendorName(deviceName);
        DeviceType deviceType = deviceType(metal, deviceName);
        Set<String> capabilities = new HashSet<>();
        capabilities.add("SDL_GPU");
        capabilities.add("Metal");
        if (metal.mac1()) capabilities.add("MTLGPUFamilyMac1");
        if (metal.mac2()) capabilities.add("MTLGPUFamilyMac2");
        if (metal.highestAppleFamily() > 0) capabilities.add("MTLGPUFamilyApple" + metal.highestAppleFamily());
        if (metal.metal3()) capabilities.add("MTLGPUFamilyMetal3");
        if (metal.metal4()) capabilities.add("MTLGPUFamilyMetal4");
        if (metal.unifiedMemory()) capabilities.add("MetalUnifiedMemory");
        if (runtime.nativeIndirectEnabled()) capabilities.add("MetalNativeIndirectVerified");
        else capabilities.add("NativeAcceleratorCpuIndirectFallback");
        if (runtime.graphicsStorageVerified()) capabilities.add("MetalGraphicsStorageVerified");
        if (runtime.textureArraysVerified()) capabilities.add("MetalTextureArraysVerified");
        if (runtime.fencesVerified()) capabilities.add("MetalFenceSubmissionVerified");
        capabilities.add("MetalAnisotropy" + runtime.maxAnisotropy() + "x");
        String driverInfo = driver + "; " + metal.familySummary()
                + "; preflightDeviceMatch=" + runtime.preflightDeviceMatches()
                + "; runtimeIndirect=" + (runtime.nativeIndirectEnabled() ? "native" : "cpu-fallback")
                + "; model=" + metal.hardwareModel() + "; macOS=" + metal.osVersion();
        this.info=new DeviceInfo(deviceName,vendor,driverInfo,true,"Metal (SDL3)",1.0f,limits,features,
                Set.copyOf(capabilities),hints,deviceType);
        int requestedFrames = Integer.getInteger("nativeaccelerator.renderer.metal.framesInFlight", 3);
        if (this.mac1Compat) {
            requestedFrames = Integer.getInteger("nativeaccelerator.renderer.metal.mac1FramesInFlight", 1);
        }
        this.framesInFlight = Math.max(1, Math.min(3, requestedFrames));
        try { SDLGPU.SDL_SetGPUAllowedFramesInFlight(handle,this.framesInFlight); } catch(Throwable ignored){}
        System.out.println("[Native Accelerator] Metal core marker: transient-retirement-fix32; framesInFlight="
                + this.framesInFlight + "; mac1Compat=" + this.mac1Compat
                + "; transientLifetime=fenced; clearedTargetCycling=" + cycleClearedTargets()
                + "; fix25Cycling=false");
    }

    private static String vendorName(String deviceName) {
        String value = deviceName.toLowerCase(Locale.ROOT);
        if (value.contains("nvidia") || value.contains("geforce")) return "NVIDIA";
        if (value.contains("amd") || value.contains("radeon")) return "AMD";
        if (value.contains("intel")) return "Intel";
        if (value.contains("apple")) return "Apple";
        return "Apple / Metal";
    }

    private static DeviceType deviceType(MacMetalCapabilities.Result metal, String deviceName) {
        if (metal.isAppleSilicon() || metal.unifiedMemory() || metal.lowPower()) return DeviceType.INTEGRATED;
        if (metal.removable()) return DeviceType.DISCRETE;
        String value = deviceName.toLowerCase(Locale.ROOT);
        if (value.contains("intel")) return DeviceType.INTEGRATED;
        if (value.contains("nvidia") || value.contains("geforce")
                || value.contains("amd") || value.contains("radeon")) return DeviceType.DISCRETE;
        return DeviceType.OTHER;
    }



    /**
     * Keep transient GPU buffers alive until the command buffer that references them has completed.
     * SDL GPU submission is asynchronous; releasing these buffers at submit time can let later frames
     * reuse/destroy resources that the Metal driver is still reading.
     */
    synchronized void retireTransientMemory(MetalTransientMemory memory, long fence) {
        if (memory == null || memory.isEmpty()) {
            if (fence != 0L) SDLGPU.SDL_ReleaseGPUFence(handle(), fence);
            return;
        }
        if (fence == 0L) throw new IllegalArgumentException("retirement fence");
        retiredTransientMemory.addLast(new RetiredTransientMemory(fence, memory));
        reapCompletedTransientMemory(false);

        // Bound retained memory even if the driver is several submissions behind. Waiting only happens
        // when the queue grows beyond the configured frame window; normal completed fences are polled.
        while (retiredTransientMemory.size() > Math.max(2, framesInFlight + 1)) {
            reapCompletedTransientMemory(true);
        }
    }

    synchronized void reapCompletedTransientMemory() {
        reapCompletedTransientMemory(false);
    }

    private void reapCompletedTransientMemory(boolean waitForOldest) {
        while (!retiredTransientMemory.isEmpty()) {
            RetiredTransientMemory oldest = retiredTransientMemory.peekFirst();
            boolean complete = SDLGPU.SDL_QueryGPUFence(handle(), oldest.fence());
            if (!complete && waitForOldest) {
                org.lwjgl.PointerBuffer fences;
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    fences = stack.mallocPointer(1).put(0, oldest.fence());
                    if (!SDLGPU.SDL_WaitForGPUFences(handle(), true, fences)) {
                        throw new IllegalStateException("SDL_WaitForGPUFences failed: " + MetalInterop.lastSdlError());
                    }
                }
                complete = true;
            }
            if (!complete) break;
            retiredTransientMemory.removeFirst();
            try { oldest.memory().release(); } finally {
                SDLGPU.SDL_ReleaseGPUFence(handle(), oldest.fence());
            }
            if (waitForOldest) break;
        }
    }

    private synchronized void releaseAllRetiredTransientMemory() {
        while (!retiredTransientMemory.isEmpty()) {
            RetiredTransientMemory retired = retiredTransientMemory.removeFirst();
            try { retired.memory().release(); } catch (Throwable ignored) {}
            try { SDLGPU.SDL_ReleaseGPUFence(handle, retired.fence()); } catch (Throwable ignored) {}
        }
    }

    private record RetiredTransientMemory(long fence, MetalTransientMemory memory) {}


    boolean mac1Compat() { return mac1Compat; }

    boolean cycleClearedTargets() {
        return mac1Compat && Boolean.parseBoolean(System.getProperty(
                "nativeaccelerator.renderer.metal.mac1CycleClearedTargets", "true"));
    }

    boolean nativeIndirectDrawSupported() { return runtime.nativeIndirectEnabled(); }
    void blacklistNativeIndirect(Throwable failure) { runtime.blacklistNativeIndirect(failure); }
    int runtimeMaxAnisotropy() { return runtime.maxAnisotropy(); }
    MetalFanIndexBuffer fanIndexBuffer() { return fanIndexBuffer; }

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
        long perfStart=MetalPerfCounters.tic();
        int perfBuffers=0;
        long perfBytes=0L;
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
                perfBuffers++;
                perfBytes += range.bytes().remaining();
                if (!buffer.hasDirtyRange()) dirtyBuffers.remove(buffer);
            }
            MetalTransfers.encodeUploadBuffersInPass(handle(), copyPass, uploads);
        } finally {
            SDLGPU.SDL_EndGPUCopyPass(copyPass);
            MetalPerfCounters.dirtyFlush(perfBuffers,perfBytes,perfStart);
        }
    }
    long handle(){if(closed.get())throw new IllegalStateException("Metal device is closed");return handle;}
    @Override public GpuSurfaceBackend createSurface(long window,BooleanSupplier iconified){return new MetalSurface(this,window,iconified);}
    @Override public CommandEncoderBackend createCommandEncoder(){reapCompletedTransientMemory();return new MetalCommandEncoder(this);}
    @Override public GpuSampler createSampler(AddressMode u,AddressMode v,FilterMode min,FilterMode mag,int maxAnisotropy,OptionalDouble maxLod){
        int clamped=Math.max(1,Math.min(maxAnisotropy,runtime.maxAnisotropy()));
        try{return new MetalSampler(this,u,v,min,mag,clamped,maxLod);}
        catch(RuntimeException | Error failure){
            if(clamped<=1)throw failure;
            runtime.blacklistAnisotropy(failure);
            return new MetalSampler(this,u,v,min,mag,1,maxLod);
        }
    }
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
    @Override public void close(){if(closed.compareAndSet(false,true)){try{SDLGPU.SDL_WaitForGPUIdle(handle);}catch(Throwable ignored){}releaseAllRetiredTransientMemory();try{fanIndexBuffer.close();}catch(Throwable ignored){}SDLGPU.SDL_DestroyGPUDevice(handle);MetalBackend.deviceClosed();}}
}
