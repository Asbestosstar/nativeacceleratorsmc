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
    public static final String ALLOW_MAC_FAMILY1_PROPERTY = "nativeaccelerator.renderer.metal.allowMacFamily1";
    /** Force an actual Metal device attempt even when SDL's non-creating support probe says no. */
    public static final String FORCE_ATTEMPT_PROPERTY = "nativeaccelerator.renderer.metal.forceAttempt";

    private static final AtomicInteger ACTIVE_DEVICES = new AtomicInteger();
    private static volatile String lastProbeReason = "not probed";

    enum MetalCapabilityTier {
        MAC2_OR_NEWER,
        MAC1_COMPAT,
        UNKNOWN
    }

    /** auto (default), on/force, or off. */
    public static boolean shouldOffer() {
        String mode = System.getProperty(ENABLE_PROPERTY, "auto").trim().toLowerCase(Locale.ROOT);
        if (mode.equals("off") || mode.equals("false") || mode.equals("0") || mode.equals("disabled")) {
            return false;
        }
        // "on" is intentionally still a capability probe. It forces ordering, not an impossible device.
        return isSupported();
    }

    /** Probe only SDL/LWJGL symbols and Metal capability properties; no device is created. */
    public static boolean isSupported() {
        try {
            if (!MetalInterop.classPresent(MetalInterop.SDL_GPU)) {
                lastProbeReason = "LWJGL SDL GPU binding is not present";
                return false;
            }
            if (MetalInterop.classPresent(MetalInterop.SDL_PROPERTIES)) {
                int props = createDeviceProperties(false);
                try {
                    boolean supported = (Boolean)MetalInterop.sdlCall("SDL_GPUSupportsProperties", props);
                    lastProbeReason = supported
                            ? "SDL reports Metal/MSL support"
                            : "SDL_GPUSupportsProperties returned false: " + MetalInterop.lastSdlError();
                    return supported;
                } finally {
                    MetalInterop.propertiesCall("SDL_DestroyProperties", props);
                }
            }
            int msl = MetalInterop.sdl("SDL_GPU_SHADERFORMAT_MSL");
            boolean supported = MetalInterop.sdlBool("SDL_GPUSupportsShaderFormats", msl, DRIVER_NAME);
            lastProbeReason = supported
                    ? "SDL reports Metal/MSL support (legacy probe)"
                    : "SDL_GPUSupportsShaderFormats returned false: " + MetalInterop.lastSdlError();
            return supported;
        } catch (Throwable unavailable) {
            String message = unavailable.getMessage();
            lastProbeReason = unavailable.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + message);
            return false;
        }
    }

    /**
     * Classify the Metal device conservatively without parsing a GPU marketing name. SDL's normal
     * Metal probe requires MacFamily2+. We then retry with the explicit MacFamily1 opt-in.
     *
     * <p>MacFamily1 must not use graphics indirect command buffers. If the strict probe cannot
     * prove MacFamily2 support, we deliberately choose the compatibility tier.</p>
     */
    static MetalCapabilityTier detectCapabilityTier() {
        if (!MetalInterop.classPresent(MetalInterop.SDL_PROPERTIES)) {
            // Old SDL did not expose the MacFamily1 opt-in path. A successful legacy Metal device
            // therefore normally implies the stricter support tier, but keep the result explicit.
            return MetalCapabilityTier.UNKNOWN;
        }
        try {
            if (supportsWithMacFamily1(false)) return MetalCapabilityTier.MAC2_OR_NEWER;
            boolean allowMacFamily1 = Boolean.parseBoolean(
                    System.getProperty(ALLOW_MAC_FAMILY1_PROPERTY, "true"));
            if (allowMacFamily1 && supportsWithMacFamily1(true)) return MetalCapabilityTier.MAC1_COMPAT;
        } catch (Throwable ignored) {
            // A real device creation can still succeed on patched/OCLP systems. Unknown is handled
            // conservatively by MetalDevice when MacFamily1 opt-in is enabled.
        }
        return MetalCapabilityTier.UNKNOWN;
    }

    private static boolean supportsWithMacFamily1(boolean allowMacFamily1) {
        int props = createDeviceProperties(false, allowMacFamily1);
        try {
            return (Boolean)MetalInterop.sdlCall("SDL_GPUSupportsProperties", props);
        } finally {
            MetalInterop.propertiesCall("SDL_DestroyProperties", props);
        }
    }

    public static String lastProbeReason() {
        return lastProbeReason;
    }

    private static int createDeviceProperties(boolean debug) {
        boolean allowMacFamily1 = Boolean.parseBoolean(System.getProperty(ALLOW_MAC_FAMILY1_PROPERTY, "true"));
        return createDeviceProperties(debug, allowMacFamily1);
    }

    private static int createDeviceProperties(boolean debug, boolean allowMacFamily1) {
        int props = ((Number)MetalInterop.propertiesCall("SDL_CreateProperties")).intValue();
        if (props == 0) throw new IllegalStateException("SDL_CreateProperties failed: " + MetalInterop.lastSdlError());
        try {
            MetalInterop.propertiesCall("SDL_SetStringProperty", props,
                    MetalInterop.sdlString("SDL_PROP_GPU_DEVICE_CREATE_NAME_STRING"), DRIVER_NAME);
            MetalInterop.propertiesCall("SDL_SetBooleanProperty", props,
                    MetalInterop.sdlString("SDL_PROP_GPU_DEVICE_CREATE_SHADERS_MSL_BOOLEAN"), true);
            MetalInterop.propertiesCall("SDL_SetBooleanProperty", props,
                    MetalInterop.sdlString("SDL_PROP_GPU_DEVICE_CREATE_DEBUGMODE_BOOLEAN"), debug);
            MetalInterop.propertiesCall("SDL_SetBooleanProperty", props,
                    MetalInterop.sdlString("SDL_PROP_GPU_DEVICE_CREATE_VERBOSE_BOOLEAN"), debug);

            // SDL normally requires Metal MacFamily2. Native Accelerator's SDL backend intentionally
            // maps RenderPearl color formats to linear UNORM/float targets rather than SDL sRGB texture
            // formats, so allow MacFamily1 by default for older Macs/OCLP. It can be disabled explicitly.
            if (allowMacFamily1) {
                MetalInterop.propertiesCall("SDL_SetBooleanProperty", props,
                        MetalInterop.sdlString("SDL_PROP_GPU_DEVICE_CREATE_METAL_ALLOW_MACFAMILY1_BOOLEAN"), true);
            }
            return props;
        } catch (Throwable t) {
            MetalInterop.propertiesCall("SDL_DestroyProperties", props);
            if (t instanceof RuntimeException runtime) throw runtime;
            if (t instanceof Error error) throw error;
            throw new IllegalStateException("Failed to configure SDL Metal device properties", t);
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
        String mode = System.getProperty(ENABLE_PROPERTY, "auto").trim().toLowerCase(Locale.ROOT);
        if (mode.equals("off") || mode.equals("false") || mode.equals("0") || mode.equals("disabled")) {
            throw new BackendCreationException(
                    "Metal backend disabled by -D" + ENABLE_PROPERTY + "=" + mode,
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
        if (!MetalInterop.classPresent(MetalInterop.SDL_GPU)) {
            throw new BackendCreationException(
                    "LWJGL SDL GPU binding is unavailable; add org.lwjgl:lwjgl-sdl:3.4.3",
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }

        boolean supported = isSupported();
        boolean forceAttempt = Boolean.parseBoolean(System.getProperty(FORCE_ATTEMPT_PROPERTY, "false"));
        if (!supported) {
            // An explicit Metal preference should be allowed to reach SDL_CreateGPUDeviceWithProperties.
            // This matters on patched/OCLP systems where capability reporting can be conservative.
            System.err.println("[Native Accelerator] SDL Metal support probe was negative ("
                    + lastProbeReason + "); attempting real device creation anyway"
                    + (forceAttempt ? " because forceAttempt=true" : " because Metal was explicitly selected"));
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
        boolean debug = options.logLevel() > 0 || options.useLabels() || options.useValidationLayers();
        MetalCapabilityTier capabilityTier = detectCapabilityTier();
        long handle;
        try {
            if (MetalInterop.classPresent(MetalInterop.SDL_PROPERTIES)) {
                int props = createDeviceProperties(debug);
                try {
                    handle = MetalInterop.sdlLong("SDL_CreateGPUDeviceWithProperties", props);
                } finally {
                    MetalInterop.propertiesCall("SDL_DestroyProperties", props);
                }
            } else {
                int msl = MetalInterop.sdl("SDL_GPU_SHADERFORMAT_MSL");
                handle = MetalInterop.sdlLong("SDL_CreateGPUDevice", msl, debug, DRIVER_NAME);
            }
        } catch (Throwable t) {
            throw new BackendCreationException("SDL3 Metal device creation failed: " + t.getMessage(),
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
        if (handle == 0L) {
            throw new BackendCreationException("SDL3 Metal device creation failed after probe '" + lastProbeReason
                    + "': " + MetalInterop.lastSdlError(),
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
        boolean allowMacFamily1 = Boolean.parseBoolean(System.getProperty(ALLOW_MAC_FAMILY1_PROPERTY, "true"));
        if (capabilityTier == MetalCapabilityTier.UNKNOWN && allowMacFamily1) {
            // The device creation itself succeeded after an inconclusive strict probe. On an OCLP or
            // otherwise patched host, treating this as Mac1 is safer than touching unsupported ICBs.
            capabilityTier = MetalCapabilityTier.MAC1_COMPAT;
        }
        System.out.println("[Native Accelerator] SDL3 Metal device created successfully; probe=" + lastProbeReason
                + ", allowMacFamily1=" + allowMacFamily1
                + ", capabilityTier=" + capabilityTier);

        try {
            MetalDevice backend = new MetalDevice(handle, options, capabilityTier);
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
