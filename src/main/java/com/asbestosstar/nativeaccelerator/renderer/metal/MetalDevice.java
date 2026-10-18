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
    private final Deque<RetiredSubmission> retiredSubmissions = new ArrayDeque<>();
    private final int framesInFlight;
    private final boolean mac1Compat;
    private volatile long lastPresentedSourceHandle;
    private final boolean cachedCycleClearedTargets;
    private final boolean cachedGuiRegionalClearUpload;
    private final boolean cachedResetSamplersPerPipeline;
    private final boolean cachedValidateBindings;
    private final boolean cachedForceRenderAreaScissor;
    private final boolean cachedMac1RetainTextureTransfers;
    private final boolean cachedMac1RegionalDepthAttachmentClear;
    private final boolean cachedMac1RelaxImplicitPassScissor;
    private final boolean cachedExplicitViewportPerPass;
    private final boolean cachedMac1SerializeBufferUploads;
    private final boolean cachedStrictSwapchainPresent;
    private final boolean cachedWaitForSwapchainBeforeAcquire;
    private final boolean cachedForcePresentedSourceFirstClear;
    private final boolean cachedMac1FencelessRing;
    private final int mac1RingSize;
    private final MetalTransientMemory[] mac1Ring;
    @SuppressWarnings("unchecked")
    private final List<Long>[] mac1RingTransfers;
    private final Runnable[] mac1RingCompletions;
    private int mac1RingHead;

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
            requestedFrames = Integer.getInteger("nativeaccelerator.renderer.metal.mac1FramesInFlight", 2);
        }
        this.framesInFlight = Math.max(1, Math.min(3, requestedFrames));
        try { SDLGPU.SDL_SetGPUAllowedFramesInFlight(handle,this.framesInFlight); } catch(Throwable ignored){}
        if (MetalTrace.enabled()) MetalTrace.log("DEVICE_READY", "handle=" + MetalTrace.hex(handle) + " name=\"" + MetalTrace.safe(deviceName) + "\" vendor=" + vendor + " mac1=" + mac1Compat + " framesInFlight=" + framesInFlight + " traceFile=\"" + MetalTrace.safe(MetalTrace.path()) + "\"");
        this.cachedCycleClearedTargets = mac1Compat && Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.mac1CycleClearedTargets", "false"));
        this.cachedGuiRegionalClearUpload = Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.guiRegionalClearUpload", "true"));
        this.cachedResetSamplersPerPipeline = Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.resetSamplersPerPipeline", "false"));
        this.cachedValidateBindings = Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.validateBindings", "false"));
        this.cachedForceRenderAreaScissor = Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.forceRenderAreaScissor", "true"));
        this.cachedMac1RetainTextureTransfers = Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.mac1RetainTextureTransfers", "false"));
        this.cachedMac1RegionalDepthAttachmentClear = Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.mac1RegionalDepthAttachmentClear", "false"));
        this.cachedMac1RelaxImplicitPassScissor = Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.mac1RelaxImplicitPassScissor", "true"));
        this.cachedExplicitViewportPerPass = Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.explicitViewportPerPass", "true"));
        this.cachedMac1SerializeBufferUploads = mac1Compat && Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.mac1SerializeBufferUploads", "false"));
        this.cachedStrictSwapchainPresent = mac1Compat && Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.mac1StrictSwapchainPresent", "false"));
        this.cachedWaitForSwapchainBeforeAcquire = mac1Compat && Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.mac1WaitForSwapchain", "false"));
        this.cachedForcePresentedSourceFirstClear = mac1Compat && Boolean.parseBoolean(System.getProperty("nativeaccelerator.renderer.metal.mac1ForcePresentedSourceFirstClear", "true"));
        this.cachedMac1FencelessRing = this.mac1Compat && Boolean.parseBoolean(
            System.getProperty("nativeaccelerator.renderer.metal.mac1FencelessRing", "false"));
        if (this.cachedMac1FencelessRing) {
            this.mac1RingSize = Math.max(32, this.framesInFlight + 28);
            this.mac1Ring = new MetalTransientMemory[this.mac1RingSize];
            this.mac1RingTransfers = new List[this.mac1RingSize];
            this.mac1RingCompletions = new Runnable[this.mac1RingSize];
        } else {
            this.mac1RingSize = 0; this.mac1Ring = null;
            this.mac1RingTransfers = null; this.mac1RingCompletions = null;
        }
        this.mac1RingHead = 0;
        MetalLogging.info("Metal core marker: residual-gui-dynamic-texture-fix51; framesInFlight="
                + this.framesInFlight + "; mac1Compat=" + this.mac1Compat
                + "; transientLifetime=" + (this.cachedMac1FencelessRing ? "fenceless" : "fenced")
                + "; mac1FencelessRing=" + this.cachedMac1FencelessRing
                + "; clearedTargetCycling=" + cycleClearedTargets()
                + "; guiRegionalClearUpload=" + guiRegionalClearUpload()
                + "; resetSamplersPerPipeline=" + resetSamplersPerPipeline()
                + "; forceRenderAreaScissor=" + forceRenderAreaScissor()
                + "; explicitViewportPerPass=" + explicitViewportPerPass()
                + "; serializeBufferUploads=" + mac1SerializeBufferUploads()
                + "; waitForSwapchain=" + waitForSwapchainBeforeAcquire()
                + "; validateBindings=" + validateBindings()
                + "; retainTextureTransfers=" + mac1RetainTextureTransfers()
                + "; regionalDepthAttachmentClear=" + mac1RegionalDepthAttachmentClear()
                + "; relaxImplicitPassScissor=" + mac1RelaxImplicitPassScissor()
                + "; strictSwapchainPresent=" + strictSwapchainPresent()
                + "; forcePresentedSourceFirstClear=" + forcePresentedSourceFirstClear()
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
     * Retire command-buffer-owned resources behind the submission fence instead of blocking the
     * render thread. This is the central frame-pacing path for legacy Mac1 GPUs: normal frames poll
     * completed fences and only wait if the driver falls farther behind than the configured
     * frames-in-flight window.
     */
    synchronized void retireTransientMemory(MetalTransientMemory memory, long fence) {
        retireSubmission(memory, fence, List.of(), null);
    }

    synchronized void retireSubmission(MetalTransientMemory memory, long fence, List<Long> textureTransfers, Runnable completion) {
        if (fence == 0L) throw new IllegalArgumentException("retirement fence");
        List<Long> transfers = textureTransfers == null || textureTransfers.isEmpty()
                ? List.of() : textureTransfers;
        boolean emptyMemory = memory == null || memory.isEmpty();
        if (emptyMemory && transfers.isEmpty() && completion == null) {
            SDLGPU.SDL_ReleaseGPUFence(handle(), fence);
            return;
        }
        retiredSubmissions.addLast(new RetiredSubmission(fence, memory, transfers, completion));
        reapCompletedTransientMemory(false);

        // Bound outstanding driver work without serializing every frame.
        while (retiredSubmissions.size() > (mac1Compat ? Math.max(4, framesInFlight + 2) : Math.max(2, framesInFlight + 1))) {
            reapCompletedTransientMemory(true);
        }
    }

    synchronized void retireSubmissionFenceless(MetalTransientMemory memory, List<Long> textureTransfers) {
        int slot = mac1RingHead % mac1RingSize;
        MetalTransientMemory old = mac1Ring[slot];
        if (old != null) { old.release(); mac1Ring[slot] = null; }
        List<Long> oldT = mac1RingTransfers[slot];
        if (oldT != null) { for (long t : oldT) MetalTransfers.releaseTransferBuffer(handle, t); mac1RingTransfers[slot] = null; }
        Runnable oldC = mac1RingCompletions[slot];
        if (oldC != null) { oldC.run(); mac1RingCompletions[slot] = null; }
        mac1Ring[slot] = memory;
        mac1RingTransfers[slot] = (textureTransfers == null || textureTransfers.isEmpty()) ? null : textureTransfers;
        mac1RingCompletions[slot] = null;
        mac1RingHead++;
    }

    boolean mac1FencelessRing() { return cachedMac1FencelessRing; }

        synchronized void reapCompletedTransientMemory() {
        reapCompletedTransientMemory(false);
    }

    synchronized void waitForOldestRetiredSubmission() {
        reapCompletedTransientMemory(true);
    }

    private void reapCompletedTransientMemory(boolean waitForOldest) {
        while (!retiredSubmissions.isEmpty()) {
            RetiredSubmission oldest = retiredSubmissions.peekFirst();
            boolean complete = SDLGPU.SDL_QueryGPUFence(handle(), oldest.fence());
            if (!complete && waitForOldest) {
                if (mac1Compat) {
                    // Yield-poll before blocking: OCLP NVIDIA Metal signals fences very slowly
                    // and a hard wait stalls the render thread for hundreds of milliseconds.
                    for (int poll = 0; poll < 8 && !complete; poll++) {
                        try { Thread.sleep(1); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        complete = SDLGPU.SDL_QueryGPUFence(handle(), oldest.fence());
                    }
                }
                if (!complete) {
                    try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                        org.lwjgl.PointerBuffer fences = stack.mallocPointer(1).put(0, oldest.fence());
                        if (!SDLGPU.SDL_WaitForGPUFences(handle(), true, fences)) {
                            throw new IllegalStateException("SDL_WaitForGPUFences failed: " + MetalInterop.lastSdlError());
                        }
                    }
                    complete = true;
                }
            }
            if (!complete) break;
            retiredSubmissions.removeFirst();
            releaseRetiredSubmission(oldest);
            if (waitForOldest) break;
        }
    }

    private void releaseRetiredSubmission(RetiredSubmission retired) {
        releaseRetiredSubmission(retired, handle());
    }

    private void releaseRetiredSubmission(RetiredSubmission retired, long rawHandle) {
        try {
            if (retired.memory() != null) retired.memory().release();
            for (long transfer : retired.textureTransfers()) {
                MetalTransfers.releaseTransferBuffer(rawHandle, transfer);
            }
            if (retired.completion() != null) retired.completion().run();
        } finally {
            SDLGPU.SDL_ReleaseGPUFence(rawHandle, retired.fence());
        }
    }

    private synchronized void releaseAllRetiredTransientMemory(long rawHandle) {
        while (!retiredSubmissions.isEmpty()) {
            RetiredSubmission retired = retiredSubmissions.removeFirst();
            // close() waits for GPU idle first, so all resources are safe to release here.
            releaseRetiredSubmission(retired, rawHandle);
        }
        if (mac1Ring != null) {
            for (int i = 0; i < mac1RingSize; i++) {
                if (mac1Ring[i] != null) { mac1Ring[i].release(); mac1Ring[i] = null; }
                if (mac1RingTransfers[i] != null) { for (long t : mac1RingTransfers[i]) MetalTransfers.releaseTransferBuffer(rawHandle, t); mac1RingTransfers[i] = null; }
                if (mac1RingCompletions[i] != null) { mac1RingCompletions[i].run(); mac1RingCompletions[i] = null; }
            }
        }
    }

    private record RetiredSubmission(long fence, MetalTransientMemory memory,
                                     List<Long> textureTransfers, Runnable completion) {}


    boolean mac1Compat() { return mac1Compat; }

    boolean cycleClearedTargets() { return cachedCycleClearedTargets; }

    boolean guiRegionalClearUpload() { return cachedGuiRegionalClearUpload; }

    boolean resetSamplersPerPipeline() { return cachedResetSamplersPerPipeline; }

    boolean validateBindings() { return cachedValidateBindings; }

    boolean forceRenderAreaScissor() { return cachedForceRenderAreaScissor; }


    boolean mac1RetainTextureTransfers() { return cachedMac1RetainTextureTransfers; }

    boolean mac1RegionalDepthAttachmentClear() { return cachedMac1RegionalDepthAttachmentClear; }

    boolean mac1RelaxImplicitPassScissor() { return cachedMac1RelaxImplicitPassScissor; }
    boolean explicitViewportPerPass() { return cachedExplicitViewportPerPass; }

    /**
     * Diagnostic/safety path for legacy MacFamily1 drivers. SDL_UploadToGPUBuffer with cycle=false
     * writes the existing backing allocation. If that allocation is still referenced by an earlier
     * command buffer, old NVIDIA/OCLP Metal drivers can display torn or stale GUI geometry.
     * Serializing only before dirty-buffer uploads preserves partial-buffer contents while proving
     * or disproving that hazard without changing the higher-level RenderPearl buffer model.
     */
    boolean mac1SerializeBufferUploads() { return cachedMac1SerializeBufferUploads; }

    boolean strictSwapchainPresent() { return cachedStrictSwapchainPresent; }

    boolean waitForSwapchainBeforeAcquire() { return cachedWaitForSwapchainBeforeAcquire; }

    boolean forcePresentedSourceFirstClear() { return cachedForcePresentedSourceFirstClear; }

    long lastPresentedSourceHandle() { return lastPresentedSourceHandle; }
    void notePresentedSourceHandle(long handle) { lastPresentedSourceHandle = handle; }

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
        if (MetalTrace.enabled()) MetalTrace.log("BUFFER_FLUSH_BEGIN", "cmd=" + MetalTrace.hex(commandBuffer) + " dirtyCount=" + dirtyBuffers.size() + " serialize=" + mac1SerializeBufferUploads());
        if (mac1SerializeBufferUploads()) {
            // Explicit compatibility/debug switch only. Production defaults avoid this global
            // drain because it destroys CPU/GPU overlap and turns chunk uploads into frame spikes.
            SDLGPU.SDL_WaitForGPUIdle(handle());
        }
        long perfStart=MetalPerfCounters.tic();
        int perfBuffers=0;
        long perfBytes=0L;
        long copyPass = SDLGPU.SDL_BeginGPUCopyPass(commandBuffer);
        if (copyPass == 0L) throw new IllegalStateException("SDL_BeginGPUCopyPass failed: " + MetalInterop.lastSdlError());
        try {
            List<MetalTransfers.BufferUpload> uploads = new ArrayList<>();
            for (MetalGpuBuffer buffer : dirtyBuffers) {
                MetalGpuBuffer.DirtyRange range = buffer.drainDirtyRange();
                if (range == null) {
                    dirtyBuffers.remove(buffer);
                    continue;
                }
                uploads.add(new MetalTransfers.BufferUpload(buffer.handle(), range.offset(), range.bytes()));
                if (MetalTrace.enabled()) MetalTrace.log("BUFFER_UPLOAD", "buffer=" + MetalTrace.hex(buffer.handle()) + " offset=" + range.offset() + " bytes=" + range.bytes().remaining());
                perfBuffers++;
                perfBytes += range.bytes().remaining();
                if (!buffer.hasDirtyRange()) dirtyBuffers.remove(buffer);
            }
            MetalTransfers.encodeUploadBuffersInPass(handle(), copyPass, uploads);
        } finally {
            SDLGPU.SDL_EndGPUCopyPass(copyPass);
            MetalPerfCounters.dirtyFlush(perfBuffers,perfBytes,perfStart);
            if (MetalTrace.enabled()) MetalTrace.log("BUFFER_FLUSH_END", "cmd=" + MetalTrace.hex(commandBuffer) + " buffers=" + perfBuffers + " bytes=" + perfBytes);
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
    @Override public GpuTexture createTexture(@Nullable String label,int usage,GpuFormat format,int width,int height,int depthOrLayers,int mipLevels){MetalGpuTexture t=new MetalGpuTexture(this,label,usage,format,width,height,depthOrLayers,mipLevels);if (MetalTrace.enabled()) MetalTrace.log("TEXTURE_CREATE", "label=\"" + MetalTrace.safe(label) + "\" handle=" + MetalTrace.hex(t.handle()) + " usage=" + usage + " format=" + format + " size=" + width + "x" + height + " layers=" + depthOrLayers + " mips=" + mipLevels);return t;}
    @Override public GpuTextureView createTextureView(GpuTexture texture,int baseMip,int mipLevels){return new MetalTextureView(texture,baseMip,mipLevels);}
    @Override public GpuBuffer createBuffer(@Nullable Supplier<String> label,int usage,long size){MetalGpuBuffer b=new MetalGpuBuffer(this,usage,size);nameBuffer(label,b);if (MetalTrace.enabled()) MetalTrace.log("BUFFER_CREATE", "handle=" + MetalTrace.hex(b.handle()) + " usage=" + usage + " size=" + size);return b;}
    @Override public GpuBuffer createBuffer(@Nullable Supplier<String> label,int usage,ByteBuffer data){MetalGpuBuffer b=new MetalGpuBuffer(this,usage,data);nameBuffer(label,b);if (MetalTrace.enabled()) MetalTrace.log("BUFFER_CREATE_DATA", "handle=" + MetalTrace.hex(b.handle()) + " usage=" + usage + " bytes=" + data.remaining());return b;}
    private void nameBuffer(@Nullable Supplier<String> label,MetalGpuBuffer b){if(label==null)return;try{String name=label.get();if(name!=null&&!name.isBlank())MetalInterop.sdlCall("SDL_SetGPUBufferName",handle(),b.handle(),name);}catch(Throwable ignored){}}
    @Override public List<String> getLastDebugMessages(){synchronized(messages){return List.copyOf(messages);}}
    @Override public boolean isDebuggingEnabled(){return debug.logLevel()>0||debug.useLabels()||debug.useValidationLayers();}
    @Override public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo createInfo){
        return ()->{try{return new MetalRenderPipeline(this,createInfo);}catch(Throwable t){MetalTrace.logError("PIPELINE_COMPILE_FAIL name=\""+MetalTrace.safe(createInfo.name())+"\"",t);String detail=t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();messages.add("Metal pipeline '"+createInfo.name()+"' failed: "+detail);return null;}};
    }
    @Override public GpuQueryPool createTimestampQueryPool(int size){return new MetalQueryPool(size);}
    @Override public long getTimestampCalibrationOffset(){return 0L;}
    @Override public DeviceInfo getDeviceInfo(){return info;}
    @Override public void close(){
        if(closed.compareAndSet(false,true)){
            // Capture the raw SDL handle before poisoning the public handle() accessor. Close must
            // drain submissions and release their fences/transfers even though normal operations
            // correctly reject a device after closed becomes true.
            final long rawHandle = handle;
            try{SDLGPU.SDL_WaitForGPUIdle(rawHandle);}catch(Throwable ignored){}
            releaseAllRetiredTransientMemory(rawHandle);
            try{fanIndexBuffer.close(rawHandle);}catch(Throwable ignored){}
            try{SDLGPU.SDL_DestroyGPUDevice(rawHandle);}catch(Throwable ignored){}
            MetalBackend.deviceClosed();
        }
    }
}

