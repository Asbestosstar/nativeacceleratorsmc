package com.asbestosstar.nativeaccelerator.renderer.metal;

import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.system.MemoryStack;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Locale;

/** Runtime sanity checks and one-run feature blacklisting for patched/spoofed Metal stacks. */
final class MetalRuntimeCapabilities {
    private final AtomicBoolean nativeIndirect;
    private final AtomicBoolean anisotropyEnabled;
    private final AtomicInteger maxAnisotropy;
    private final boolean graphicsStorage;
    private final boolean textureArrays;
    private final boolean fences;
    private final String actualDeviceName;
    private final boolean preflightDeviceMatches;

    private MetalRuntimeCapabilities(boolean nativeIndirect, int maxAnisotropy,
                                     boolean graphicsStorage, boolean textureArrays, boolean fences,
                                     String actualDeviceName, boolean preflightDeviceMatches) {
        this.nativeIndirect = new AtomicBoolean(nativeIndirect);
        this.maxAnisotropy = new AtomicInteger(Math.max(1, maxAnisotropy));
        this.anisotropyEnabled = new AtomicBoolean(maxAnisotropy > 1);
        this.graphicsStorage = graphicsStorage;
        this.textureArrays = textureArrays;
        this.fences = fences;
        this.actualDeviceName = actualDeviceName == null ? "" : actualDeviceName;
        this.preflightDeviceMatches = preflightDeviceMatches;
    }

    static MetalRuntimeCapabilities probe(long device, MetalBackend.MetalCapabilityTier tier,
                                          MacMetalCapabilities.Result metal) {
        boolean storage = probeGraphicsStorage(device);
        boolean arrays = probeTextureArray(device);
        boolean fence = probeFenceSubmission(device);
        int aniso = probeAnisotropy(device);
        String actualName = actualDeviceName(device);
        boolean sameDevice = sameDeviceName(metal.deviceName(), actualName);
        boolean actualNvidia = MacMetalCapabilities.isLegacyNvidiaDeviceName(actualName);
        boolean nativeIndirect = (tier == MetalBackend.MetalCapabilityTier.APPLE_SILICON
                || tier == MetalBackend.MetalCapabilityTier.MAC2_OR_NEWER)
                && metal.indirectCommandBufferSupported()
                && !metal.legacyNvidia()
                && !actualNvidia
                && sameDevice
                && !Boolean.getBoolean("nativeaccelerator.renderer.metal.forceCpuIndirect");

        if (!storage) throw new IllegalStateException("Metal runtime probe failed: graphics storage buffers unavailable");
        if (!arrays) throw new IllegalStateException("Metal runtime probe failed: 2D texture arrays unavailable");
        if (!fence) throw new IllegalStateException("Metal runtime probe failed: command submission/fence path unavailable");

        System.out.println("[Native Accelerator] Metal runtime verification: storage=pass, textureArrays=pass, fences=pass"
                + ", anisotropy=" + aniso + "x"
                + ", SDLdevice=" + (actualName.isBlank() ? "unknown" : actualName)
                + ", preflightDeviceMatch=" + sameDevice
                + ", actualNvidia=" + actualNvidia
                + ", nativeIndirect=" + (nativeIndirect ? "verified" : "CPU-fallback")
                + " (ICB query: " + metal.indirectCommandBufferSupportDetail() + ")");
        if (!sameDevice && metal.available() && !actualName.isBlank()) {
            System.out.println("[Native Accelerator] Metal preflight/SDL device mismatch: preflight='"
                    + metal.deviceName() + "', SDL='" + actualName
                    + "'; native indirect is disabled because the preflight capability query covered a different GPU");
        }
        return new MetalRuntimeCapabilities(nativeIndirect, aniso, storage, arrays, fence, actualName, sameDevice);
    }

    boolean nativeIndirectEnabled() { return nativeIndirect.get(); }
    boolean graphicsStorageVerified() { return graphicsStorage; }
    boolean textureArraysVerified() { return textureArrays; }
    boolean fencesVerified() { return fences; }
    String actualDeviceName() { return actualDeviceName; }
    boolean preflightDeviceMatches() { return preflightDeviceMatches; }
    int maxAnisotropy() { return anisotropyEnabled.get() ? maxAnisotropy.get() : 1; }

    void blacklistNativeIndirect(Throwable failure) {
        if (nativeIndirect.compareAndSet(true, false)) {
            System.err.println("[Native Accelerator] Metal feature blacklisted for this run: native indirect draws; "
                    + "falling back to CPU-decoded direct draws. Cause: " + summarize(failure));
        }
    }

    void blacklistAnisotropy(Throwable failure) {
        if (anisotropyEnabled.compareAndSet(true, false)) {
            System.err.println("[Native Accelerator] Metal feature blacklisted for this run: anisotropic sampling; "
                    + "falling back to 1x. Cause: " + summarize(failure));
        }
    }


    private static String actualDeviceName(long device) {
        try {
            int props = MetalInterop.sdlInt("SDL_GetGPUDeviceProperties", device);
            if (props == 0) return "";
            Object value = MetalInterop.propertiesCall("SDL_GetStringProperty", props,
                    MetalInterop.sdlString("SDL_PROP_GPU_DEVICE_NAME_STRING"), "");
            return value == null ? "" : value.toString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean sameDeviceName(String preflight, String actual) {
        if (actual == null || actual.isBlank()) return false;
        if (preflight == null || preflight.isBlank() || preflight.equalsIgnoreCase("unknown")
                || preflight.equalsIgnoreCase("none") || preflight.equalsIgnoreCase("Metal GPU")) return false;
        return normalizeDeviceName(preflight).equals(normalizeDeviceName(actual));
    }

    private static String normalizeDeviceName(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().replaceAll(" +", " ");
    }

    private static boolean probeGraphicsStorage(long device) {
        long buffer = 0L;
        Object info = MetalInterop.calloc("SDL_GPUBufferCreateInfo");
        try {
            MetalInterop.set(info, "usage", MetalInterop.sdl("SDL_GPU_BUFFERUSAGE_GRAPHICS_STORAGE_READ"));
            MetalInterop.set(info, "size", 256);
            buffer = MetalInterop.sdlLong("SDL_CreateGPUBuffer", device, info);
            return buffer != 0L;
        } catch (Throwable ignored) {
            return false;
        } finally {
            MetalInterop.free(info);
            if (buffer != 0L) try { MetalInterop.sdlCall("SDL_ReleaseGPUBuffer", device, buffer); } catch (Throwable ignored) {}
        }
    }

    private static boolean probeTextureArray(long device) {
        long texture = 0L;
        Object info = MetalInterop.calloc("SDL_GPUTextureCreateInfo");
        try {
            MetalInterop.set(info, "type", MetalInterop.sdl("SDL_GPU_TEXTURETYPE_2D_ARRAY"));
            MetalInterop.set(info, "format", MetalInterop.sdl("SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM"));
            MetalInterop.set(info, "usage", MetalInterop.sdl("SDL_GPU_TEXTUREUSAGE_SAMPLER"));
            MetalInterop.set(info, "width", 2);
            MetalInterop.set(info, "height", 2);
            MetalInterop.set(info, "layer_count_or_depth", 2);
            MetalInterop.set(info, "num_levels", 1);
            MetalInterop.set(info, "sample_count", MetalInterop.sdl("SDL_GPU_SAMPLECOUNT_1"));
            texture = MetalInterop.sdlLong("SDL_CreateGPUTexture", device, info);
            return texture != 0L;
        } catch (Throwable ignored) {
            return false;
        } finally {
            MetalInterop.free(info);
            if (texture != 0L) try { MetalInterop.sdlCall("SDL_ReleaseGPUTexture", device, texture); } catch (Throwable ignored) {}
        }
    }

    private static boolean probeFenceSubmission(long device) {
        long command = 0L;
        long fence = 0L;
        try {
            command = SDLGPU.SDL_AcquireGPUCommandBuffer(device);
            if (command == 0L) return false;
            fence = SDLGPU.SDL_SubmitGPUCommandBufferAndAcquireFence(command);
            command = 0L;
            if (fence == 0L) return false;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer fences = stack.mallocPointer(1).put(0, fence);
                return SDLGPU.SDL_WaitForGPUFences(device, true, fences);
            }
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (command != 0L) try { SDLGPU.SDL_CancelGPUCommandBuffer(command); } catch (Throwable ignored) {}
            if (fence != 0L) try { SDLGPU.SDL_ReleaseGPUFence(device, fence); } catch (Throwable ignored) {}
        }
    }

    private static int probeAnisotropy(long device) {
        for (int candidate : new int[]{16, 8, 4, 2}) {
            long sampler = 0L;
            Object info = MetalInterop.calloc("SDL_GPUSamplerCreateInfo");
            try {
                MetalInterop.set(info, "min_filter", MetalInterop.sdl("SDL_GPU_FILTER_LINEAR"));
                MetalInterop.set(info, "mag_filter", MetalInterop.sdl("SDL_GPU_FILTER_LINEAR"));
                MetalInterop.set(info, "mipmap_mode", MetalInterop.sdl("SDL_GPU_SAMPLERMIPMAPMODE_LINEAR"));
                int repeat = MetalInterop.sdl("SDL_GPU_SAMPLERADDRESSMODE_REPEAT");
                MetalInterop.set(info, "address_mode_u", repeat);
                MetalInterop.set(info, "address_mode_v", repeat);
                MetalInterop.set(info, "address_mode_w", repeat);
                MetalInterop.set(info, "mip_lod_bias", 0.0f);
                MetalInterop.set(info, "max_anisotropy", (float)candidate);
                MetalInterop.set(info, "compare_op", MetalInterop.sdl("SDL_GPU_COMPAREOP_ALWAYS"));
                MetalInterop.set(info, "min_lod", 0.0f);
                MetalInterop.set(info, "max_lod", 16.0f);
                MetalInterop.set(info, "enable_anisotropy", true);
                MetalInterop.set(info, "enable_compare", false);
                sampler = MetalInterop.sdlLong("SDL_CreateGPUSampler", device, info);
                if (sampler != 0L) return candidate;
            } catch (Throwable ignored) {
            } finally {
                MetalInterop.free(info);
                if (sampler != 0L) try { MetalInterop.sdlCall("SDL_ReleaseGPUSampler", device, sampler); } catch (Throwable ignored) {}
            }
        }
        return 1;
    }

    private static String summarize(Throwable failure) {
        if (failure == null) return "unknown failure";
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
