package com.asbestosstar.nativeaccelerator.renderer;

import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererCapabilities;
import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;

import java.lang.foreign.MemorySegment;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-side bootstrap for the independent Vulkan renderer subsystem.
 *
 * <p>Two runtime gates must both pass before the client renderer activates, and neither of them is an
 * operating-system check:</p>
 * <ol>
 *   <li><b>Vulkan device evidence</b>: the renderer companion library must open a Vulkan loader and
 *       enumerate at least one physical device. A Mesa, vendor, or translation-layer build compiled
 *       without usable Vulkan support fails here even on an operating system where Vulkan is common.</li>
 *   <li><b>Minecraft backend evidence</b>: the game own options.txt must not select a non-Vulkan
 *       backend, because Native Accelerator has no OpenGL surface fallback path.</li>
 * </ol>
 *
 * <p>When the evidence says the client renderer gives no benefit, the client still starts, plays
 * normally, and keeps the full native compute accelerator.</p>
 */
public final class NativeVulkanRenderer {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static volatile MemorySegment context = MemorySegment.NULL;

    private NativeVulkanRenderer() {}

    /** True when the runtime evidence permits the client renderer path on this machine. */
    public static boolean platformEligible() {
        return RendererPlatformPolicy.clientEligible();
    }

    /** The evidence summary that produced the current decision, for diagnostics. */
    public static String evidenceSummary() {
        return RendererPlatformPolicy.describe();
    }

    public static void initializeIfEnabled() {
        if (!INITIALIZED.compareAndSet(false, true)) return;
        String mode = com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.stringValue("renderer.vulkan", "auto")
                .trim().toLowerCase(Locale.ROOT);
        if (mode.equals("off") || mode.equals("false") || mode.equals("disabled")) return;

        boolean forced = mode.equals("on") || mode.equals("true") || mode.equals("required");

        // Runtime evidence gate. The decision comes from a real Vulkan device probe and from the
        // backend Minecraft itself is configured to use, never from the operating system name.
        if (!RendererPlatformPolicy.clientEligible()) {
            String detail = RendererPlatformPolicy.describe();
            if (forced) {
                System.err.println("[Native Accelerator] Renderer was forced on, but the evidence says the client "
                        + "Vulkan renderer gives no benefit here (" + detail + "); the client renderer is not selected.");
            } else {
                System.out.println("[Native Accelerator] Client renderer skipped (evidence): " + detail
                        + ". The client runs normally; the native compute accelerator is unaffected. Set -D"
                        + RendererPlatformPolicy.ROLE_PROPERTY + "=client to attempt the client renderer anyway.");
            }
            return;
        }

        RendererNativeApi loaded = RendererPlatformPolicy.nativeApi().orElse(null);
        if (loaded == null) {
            String reason = RendererPlatformPolicy.libraryUnavailableReason().orElse("unknown reason");
            if (forced) {
                throw new IllegalStateException("Vulkan renderer was required, but the renderer companion library is unavailable: " + reason);
            }
            System.err.println("[Native Accelerator] Vulkan renderer unavailable; default renderer remains active: " + reason);
            return;
        }

        try {
            int deviceCount = loaded.vulkanPhysicalDeviceCount();
            if (deviceCount == 0) {
                if (forced) {
                    throw new IllegalStateException("Vulkan renderer was required, but no Vulkan physical device was found");
                }
                System.out.println("[Native Accelerator] Client renderer skipped: no Vulkan physical device on this machine. "
                        + "Client runs normally; native compute accelerator is unaffected.");
                return;
            }
            int workers = com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.intValue("renderer.workers", 0, 0);
            context = loaded.createContext(Math.max(0, workers));
            System.out.println("[Native Accelerator] Renderer backend: " + loaded.backendName());
            System.out.println("[Native Accelerator] Renderer evidence: " + loaded.rendererPlatformName()
                    + " role=" + (loaded.rendererClientEligible() ? "client-eligible" : "server-only")
                    + " evidence=0x" + Integer.toHexString(loaded.rendererPlatformEvidence()));
            System.out.println("[Native Accelerator] Vulkan loader: " + loaded.vulkanLoaderName()
                    + " api=0x" + Integer.toHexString(loaded.vulkanLoaderApiVersion())
                    + " devices=" + deviceCount);
            RendererPlatformPolicy.minecraftOptionsPath().ifPresent(
                    path -> System.out.println("[Native Accelerator] Minecraft options.txt: " + path));
            System.out.println("[Native Accelerator] Renderer capabilities: " + RendererCapabilities.names(loaded.capabilities()));
            System.out.println("[Native Accelerator] Renderer workers: " + loaded.contextWorkerCount(context));
        } catch (Throwable t) {
            context = MemorySegment.NULL;
            if (forced) throw t instanceof RuntimeException re ? re : new IllegalStateException(t);
            System.err.println("[Native Accelerator] Vulkan renderer unavailable; default renderer remains active: " + t.getMessage());
        }
    }

    public static Optional<RendererNativeApi> api() {
        return RendererPlatformPolicy.nativeApi().filter(ignored -> active());
    }

    public static boolean active() {
        return !context.equals(MemorySegment.NULL);
    }

    public static NativeScene createScene(int initialCapacity) {
        RendererNativeApi loaded = requireApi();
        return new NativeScene(loaded, initialCapacity);
    }

    public static NativeGpuArena createGpuArena(long capacity, long defaultAlignment) {
        RendererNativeApi loaded = requireApi();
        return new NativeGpuArena(loaded, capacity, defaultAlignment);
    }

    public static void buildVoxelFaceMasks(MemorySegment outMasks, MemorySegment occupancy, MemorySegment neighborPlanes) {
        RendererNativeApi loaded = requireApi();
        loaded.voxelFaceMasks(outMasks, occupancy, neighborPlanes);
    }

    public static void buildVoxelFaceMasksBatch(MemorySegment outMasks, MemorySegment occupancy,
                                                MemorySegment neighborPlanes, int sectionCount) {
        RendererNativeApi loaded = requireApi();
        loaded.voxelFaceMasksBatch(context, outMasks, occupancy, neighborPlanes, sectionCount);
    }

    private static RendererNativeApi requireApi() {
        if (!active()) throw new IllegalStateException("Native Vulkan renderer is not active");
        return RendererPlatformPolicy.nativeApi().orElseThrow(
                () -> new IllegalStateException("Native Vulkan renderer is not active"));
    }
}

