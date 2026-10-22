package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** RenderPearl surface boundary for a borrowed device core and the actual SDL window. */
final class NativeVulkanSurface implements GpuSurfaceBackend {
    private final NativeVulkanDeviceCore core;
    private final BooleanSupplier iconified;
    private final NativeVulkanOwnedWsiBootstrap wsi;
    private final NativeVulkanSwapchainSession swapchain;
    private final NativeVulkanFrameSubmission submission;
    private final NativeVulkanCommandEncoder encoder;
    private final NativeVulkanDevice owner;
    private GpuSurface.Configuration configuration;
    private boolean acquired;
    private boolean suboptimal;
    private boolean blitted;
    private boolean closed;

    NativeVulkanSurface(NativeVulkanDeviceCore core, NativeVulkanOwnedWsiBootstrap wsi,
                        BooleanSupplier iconified, NativeVulkanDevice owner) {
        this.core = Objects.requireNonNull(core, "core");
        this.wsi = Objects.requireNonNull(wsi, "wsi");
        this.iconified = Objects.requireNonNull(iconified, "iconified");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.swapchain = new NativeVulkanSwapchainSession(wsi, core.state(), 2);
        try {
            this.submission = new NativeVulkanFrameSubmission(wsi, core.state(), swapchain);
            this.encoder = new NativeVulkanCommandEncoder(core.state(), submission,
                    core.bootstrap(), core.allocator());
        } catch (Throwable failure) {
            swapchain.close();
            throw failure;
        }
    }

    @Override
    public synchronized void configure(GpuSurface.Configuration config) throws SurfaceException {
        requireOpen();
        Objects.requireNonNull(config, "config");
        if (acquired) throw new SurfaceException("Cannot configure an acquired Native Vulkan surface");
        if (config.width() <= 0 || config.height() <= 0) throw new SurfaceException("Surface dimensions must be positive");
        try { swapchain.configure(config.width(), config.height(), config.presentMode()); configuration = config; suboptimal = false; }
        catch (Throwable failure) { throw new SurfaceException(failure); }
    }

    @Override public synchronized boolean isSuboptimal() { return suboptimal; }

    @Override
    public synchronized void acquireNextTexture() throws SurfaceException {
        requireOpen();
        if (configuration == null) throw new SurfaceException("Native Vulkan surface is not configured");
        if (acquired) throw new SurfaceException("Native Vulkan surface image is already acquired");
        if (iconified.getAsBoolean()) throw new SurfaceException("Native Vulkan surface is iconified");
        try {
            if (owner.hasActiveSurfaceFrame()) {
                throw new IllegalStateException("A Native Vulkan surface frame is already active on this device");
            }
            NativeVulkanSwapchainPolicy.Result result = swapchain.acquireNextTexture();
            if (result == NativeVulkanSwapchainPolicy.Result.OUT_OF_DATE || result == NativeVulkanSwapchainPolicy.Result.SUBOPTIMAL) suboptimal = true;
            if (result == NativeVulkanSwapchainPolicy.Result.OUT_OF_DATE || result == NativeVulkanSwapchainPolicy.Result.SURFACE_LOST || result == NativeVulkanSwapchainPolicy.Result.DEVICE_LOST) {
                if (result == NativeVulkanSwapchainPolicy.Result.DEVICE_LOST) core.state().markLost("Swapchain acquire returned DEVICE_LOST");
                throw new SurfaceException("Native Vulkan swapchain acquire returned " + result);
            }
            try {
                submission.begin();
                acquired = true;
                owner.activateSurfaceFrame(this);
            } catch (Throwable failure) {
                if (acquired) owner.deactivateSurfaceFrame(this);
                acquired = false;
                core.state().markLost("Command recording failed after image acquisition");
                throw failure;
            }
        } catch (SurfaceException failure) { throw failure; }
        catch (Throwable failure) { throw new SurfaceException(failure); }
    }

    @Override
    public synchronized void blitFromTexture(CommandEncoderBackend encoderBackend, GpuTextureView textureView) {
        requireOpen();
        if (!acquired) throw new IllegalStateException("Native Vulkan surface is not acquired");
        if (blitted) throw new IllegalStateException("Native Vulkan surface was already blitted");
        if (!owner.ownsCommandEncoder(encoderBackend)) {
            throw new IllegalArgumentException("Foreign command encoder passed to Native Vulkan surface");
        }
        if (!(textureView instanceof NativeVulkanTextureView view)) throw new IllegalArgumentException("Foreign texture view passed to Native Vulkan surface");
        encoder.blitToSwapchain(submission.commandBuffer(), swapchain, view);
        blitted = true;
    }

    @Override
    public synchronized void present() {
        requireOpen();
        if (!acquired || !blitted) throw new IllegalStateException("Native Vulkan surface frame is incomplete");
        try {
            submission.endAndSubmit();
            NativeVulkanSwapchainPolicy.Result result = swapchain.present();
            submission.presentSubmittedFrame();
            if (result == NativeVulkanSwapchainPolicy.Result.SUBOPTIMAL || result == NativeVulkanSwapchainPolicy.Result.OUT_OF_DATE) suboptimal = true;
            if (result == NativeVulkanSwapchainPolicy.Result.SURFACE_LOST || result == NativeVulkanSwapchainPolicy.Result.DEVICE_LOST || result == NativeVulkanSwapchainPolicy.Result.FAILURE) {
                core.state().markLost("Native Vulkan present returned " + result);
                throw new IllegalStateException("Native Vulkan present returned " + result);
            }
        } catch (Throwable failure) {
            core.state().markLost("Native Vulkan frame submission/presentation failed: " + failure.getMessage());
            throw failure;
        } finally {
            owner.deactivateSurfaceFrame(this);
            acquired = false;
            blitted = false;
        }
    }

    @Override public synchronized Collection<GpuSurface.PresentMode> supportedPresentModes() {
        requireOpen();
        return List.copyOf(swapchain.supportedPresentModes());
    }

    synchronized CommandEncoderBackend encoder() {
        requireOpen();
        return encoder;
    }

    synchronized boolean isAcquired() {
        return acquired && owner.ownsActiveSurfaceFrame(this);
    }

    synchronized boolean isFrameRecording() {
        return acquired && submission.isRecording();
    }

    synchronized boolean isEncoderOpen() {
        return !closed && encoder.isOpen();
    }


    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        owner.deactivateSurfaceFrame(this);
        Throwable first = null;
        try { encoder.close(); } catch (Throwable failure) { first = failure; }
        try { submission.close(); } catch (Throwable failure) { if (first == null) first = failure; else first.addSuppressed(failure); }
        try { swapchain.close(); } catch (Throwable failure) { if (first == null) first = failure; else first.addSuppressed(failure); }
        if (first != null) throw new IllegalStateException("Native Vulkan surface cleanup failed", first);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Native Vulkan surface is closed");
        core.state().requireOpen();
    }
}
