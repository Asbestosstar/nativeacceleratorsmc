package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

/** Own-device RenderPearl Vulkan backend. */
public final class NativeVulkanGpuBackend implements GpuBackend {
    private NativeVulkanDeviceCore core;
    private NativeVulkanOwnedWsiBootstrap ownedWsi;
    private NativeVulkanDevice nativeDevice;
    private NativeVulkanOwnershipToken ownership;
    private GpuDebugOptions debugOptions;
    private long window;

    @Override public String getName() { return NativeVulkanBackendPolicy.BACKEND_NAME; }

    @Override public synchronized void loadLibrary() throws BackendCreationException {
        if (ownership != null) return;
        NativeVulkanOwnershipToken token = NativeVulkanOwnershipToken.claim();
        try {
            NativeVulkanDeviceBootstrap.probeLoaderApiVersion();
            ownership = token;
        } catch (Throwable failure) {
            token.release();
            if (failure instanceof BackendCreationException creation) throw creation;
            throw new BackendCreationException("Native Vulkan loader initialization failed: " + failure,
                    BackendCreationException.Reason.VULKAN_LOADER_MISSING);
        }
    }

    @Override public synchronized void unloadLibrary() {
        Throwable first = null;
        try { if (nativeDevice != null) nativeDevice.detachWsi(ownedWsi); }
        catch (Throwable failure) { first = append(first, failure); }
        try { if (ownedWsi != null) ownedWsi.close(); } catch (Throwable failure) {
            first = append(first, failure);
        }
        try { if (core != null) core.close(); } catch (Throwable failure) {
            first = append(first, failure);
        }
        core = null;
        ownedWsi = null;
        nativeDevice = null;
        window = 0L;
        debugOptions = null;
        if (ownership != null) {
            try { ownership.release(); } catch (Throwable failure) { first = append(first, failure); }
            ownership = null;
        }
        if (first != null) throw new IllegalStateException("Native Vulkan backend cleanup failed", first);
    }

    @Override public synchronized long createWindow(@Nullable String title, int width, int height, long flags) {
        if (ownership == null) throw new IllegalStateException("Native Vulkan library is not loaded");
        if (window != 0L) throw new IllegalStateException("Native Vulkan window was already created");
        long created = SDLVideo.SDL_CreateWindow(title == null ? "" : title, width, height, flags);
        if (created == 0L) {
            throw new IllegalStateException("SDL_CreateWindow failed: " + org.lwjgl.sdl.SDLError.SDL_GetError());
        }
        window = created;
        try {
            initializeDevice(created);
            if (ownedWsi == null || core == null) {
                throw new BackendCreationException("Native Vulkan WSI attachment did not complete",
                        BackendCreationException.Reason.PLATFORM_ERROR);
            }
            return created;
        } catch (Throwable failure) {
            Throwable cleanupFailure = null;
            if (ownedWsi != null) {
                try { ownedWsi.close(); } catch (Throwable cleanup) { cleanupFailure = cleanup; }
                ownedWsi = null;
            }
            if (core != null) {
                try { core.close(); } catch (Throwable cleanup) { cleanupFailure = append(cleanupFailure, cleanup); }
                core = null;
            }
            if (ownership != null) {
                try { ownership.release(); } catch (Throwable cleanup) { cleanupFailure = append(cleanupFailure, cleanup); }
                ownership = null;
            }
            try { SDLVideo.SDL_DestroyWindow(created); }
            catch (Throwable cleanup) { cleanupFailure = append(cleanupFailure, cleanup); }
            window = 0L;
            if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
            throw new IllegalStateException("Native Vulkan device initialization failed after window creation", failure);
        }
    }

    @Override public synchronized GpuDevice createDevice(GpuDebugOptions options) throws BackendCreationException {
        if (ownership == null) loadLibrary();
        if (core != null) throw new BackendCreationException("Native Vulkan device was already created",
                BackendCreationException.Reason.PLATFORM_ERROR);
        if (window != 0L) throw new BackendCreationException("Native Vulkan window was created before device selection",
                BackendCreationException.Reason.PLATFORM_ERROR);
        debugOptions = options;
        NativeVulkanDeviceBootstrap bootstrap = null;
        NativeVulkanDeviceCore createdCore = null;
        try {
            bootstrap = NativeVulkanDeviceBootstrap.createWithWsiExtensions(
                    options, SdlVulkanWsiBridge.requiredInstanceExtensions());
            createdCore = new NativeVulkanDeviceCore(bootstrap, new NativeVulkanDeviceState());
            NativeVulkanDeviceInfo.fromBootstrap(bootstrap);
            NativeVulkanDevice createdDevice = new NativeVulkanDevice(
                    createdCore.state(), NativeVulkanDeviceInfo.fromBootstrap(bootstrap), createdCore);
            FrontendGpuDevice frontend = new FrontendGpuDevice(createdDevice);
            nativeDevice = createdDevice;
            core = createdCore;
            return frontend;
        } catch (Throwable failure) {
            if (createdCore != null) {
                try { createdCore.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            } else if (bootstrap != null) {
                try { bootstrap.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            core = null;
            if (ownership != null) {
                try { ownership.release(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                ownership = null;
            }
            if (failure instanceof BackendCreationException creation) throw creation;
            throw new BackendCreationException("Could not initialize Native Vulkan RenderPearl device: " + failure,
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }

    private void initializeDevice(long actualWindow) throws BackendCreationException {
        if (core == null) throw new BackendCreationException("Native Vulkan device core is not initialized",
                BackendCreationException.Reason.PLATFORM_ERROR);
        NativeVulkanOwnedWsiBootstrap attached = NativeVulkanOwnedWsiBootstrap.attach(
                debugOptions, actualWindow, core);
        try {
            if (nativeDevice == null) {
                throw new BackendCreationException("Native Vulkan RenderPearl device is not initialized",
                        BackendCreationException.Reason.PLATFORM_ERROR);
            }
            nativeDevice.attachWsi(attached);
            NativeVulkanOwnershipToken token = ownership;
            if (token == null || !NativeVulkanOwnership.commit(token.token)) {
                throw new BackendCreationException("Could not commit Native Vulkan ownership",
                        BackendCreationException.Reason.PLATFORM_ERROR);
            }
            ownedWsi = attached;
            ownership = null;
        } catch (Throwable failure) {
            try { attached.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof BackendCreationException creation) throw creation;
            throw new BackendCreationException("Could not attach Native Vulkan WSI: " + failure,
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static final class NativeVulkanOwnershipToken {
        private final String token;
        private boolean released;
        private NativeVulkanOwnershipToken(String token) { this.token = token; }
        static NativeVulkanOwnershipToken claim() throws BackendCreationException {
            String token = NativeVulkanOwnership.tryClaimToken();
            if (token == null) throw new BackendCreationException("Another Native Vulkan device owns this process",
                    BackendCreationException.Reason.PLATFORM_ERROR);
            return new NativeVulkanOwnershipToken(token);
        }
        void release() {
            if (released) return;
            NativeVulkanOwnership.releaseFailedAttempt(token);
            released = true;
        }
    }
}
