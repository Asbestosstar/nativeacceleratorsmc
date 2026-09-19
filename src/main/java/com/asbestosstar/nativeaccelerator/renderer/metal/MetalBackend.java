package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RenderPearl backend backed by SDL3's Metal GPU driver.
 *
 * <p>The backend is deliberately selected by a runtime capability probe rather than by an operating
 * system name. SDL's driver probe is authoritative: a host that exposes a usable {@code metal}
 * driver is eligible, and a host that does not is not.</p>
 */
public final class MetalBackend implements GpuBackend {
    public static final String ENABLE_PROPERTY = "nativeaccelerator.renderer.metal";
    public static final String DRIVER_NAME = "metal";

    private static final AtomicInteger ACTIVE_DEVICES = new AtomicInteger();

    /** auto (default), on/force, or off. */
    public static boolean shouldOffer() {
        String mode = System.getProperty(ENABLE_PROPERTY, "auto").trim().toLowerCase(Locale.ROOT);
        if (mode.equals("off") || mode.equals("false") || mode.equals("0") || mode.equals("disabled")) {
            return false;
        }
        // "on" is intentionally still a capability probe. It forces ordering, not an impossible device.
        return isSupported();
    }

    /** Probe only SDL/LWJGL symbols and Metal shader-format support; no device is created. */
    public static boolean isSupported() {
        try {
            if (!MetalInterop.classPresent(MetalInterop.SDL_GPU)) return false;
            int msl = MetalInterop.sdl("SDL_GPU_SHADERFORMAT_MSL");
            return MetalInterop.sdlBool("SDL_GPUSupportsShaderFormats", msl, DRIVER_NAME);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** True while at least one Native Accelerator SDL/Metal device is alive. */
    public static boolean isActive() {
        return ACTIVE_DEVICES.get() > 0;
    }

    static void deviceOpened() {
        ACTIVE_DEVICES.incrementAndGet();
    }

    static void deviceClosed() {
        ACTIVE_DEVICES.updateAndGet(value -> Math.max(0, value - 1));
    }

    @Override
    public String getName() {
        return "Metal (SDL3 GPU)";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        if (!isSupported()) {
            throw new BackendCreationException(
                    "SDL3 does not expose a usable Metal GPU driver with MSL shader support",
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }

    @Override
    public void unloadLibrary() {
        // Minecraft owns SDL's process lifetime. Never SDL_Quit() from a backend candidate.
    }

    @Override
    public long createWindow(@Nullable String title, int width, int height, long flags) {
        Object result = MetalInterop.videoCall("SDL_CreateWindow", title == null ? "" : title, width, height, flags);
        return result == null ? 0L : ((Number)result).longValue();
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions options) throws BackendCreationException {
        int msl = MetalInterop.sdl("SDL_GPU_SHADERFORMAT_MSL");
        boolean debug = options.logLevel() > 0 || options.useLabels() || options.useValidationLayers();
        long handle;
        try {
            handle = MetalInterop.sdlLong("SDL_CreateGPUDevice", msl, debug, DRIVER_NAME);
        } catch (Throwable t) {
            throw new BackendCreationException("SDL3 Metal device creation failed: " + t.getMessage(),
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
        if (handle == 0L) {
            throw new BackendCreationException("SDL3 Metal device creation failed: " + MetalInterop.lastSdlError(),
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }

        try {
            MetalDevice backend = new MetalDevice(handle, options);
            GpuDevice frontend = new FrontendGpuDevice(backend);
            deviceOpened();
            return frontend;
        } catch (Throwable t) {
            try {
                MetalInterop.sdlCall("SDL_DestroyGPUDevice", handle);
            } catch (Throwable ignored) {
            }
            throw new BackendCreationException("Failed to initialize RenderPearl Metal backend: " + t.getMessage(),
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }
}
