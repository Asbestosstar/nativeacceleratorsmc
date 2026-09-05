package com.asbestosstar.nativeaccelerator.renderer;

import com.asbestosstar.nativeaccelerator.renderer.nativeapi.PanamaRendererNativeApi;
import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererCapabilities;
import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;

import java.lang.foreign.MemorySegment;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-side bootstrap for the independent Vulkan renderer subsystem.
 * The renderer is selected by runtime Vulkan-loader availability, not an OS allow-list.
 */
public final class NativeVulkanRenderer {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static volatile PanamaRendererNativeApi api;
    private static volatile MemorySegment context = MemorySegment.NULL;

    private NativeVulkanRenderer() {}

    public static void initializeIfEnabled() {
        if (!INITIALIZED.compareAndSet(false, true)) return;
        String mode = System.getProperty("nativeaccelerator.renderer.vulkan", "auto")
                .trim().toLowerCase(Locale.ROOT);
        if (mode.equals("off") || mode.equals("false") || mode.equals("disabled")) return;

        try {
            PanamaRendererNativeApi loaded = PanamaRendererNativeApi.loadBundled();
            if (!loaded.vulkanLoaderAvailable()) {
                loaded.close();
                if (mode.equals("on") || mode.equals("true") || mode.equals("required")) {
                    throw new IllegalStateException("Vulkan renderer was required, but no Vulkan loader could be opened");
                }
                return;
            }
            int deviceCount = loaded.vulkanPhysicalDeviceCount();
            if (deviceCount == 0) {
                loaded.close();
                if (mode.equals("on") || mode.equals("true") || mode.equals("required")) {
                    throw new IllegalStateException("Vulkan renderer was required, but the loader exposed no physical devices");
                }
                return;
            }
            int workers = Integer.getInteger("nativeaccelerator.renderer.workers", 0);
            MemorySegment rendererContext = loaded.createContext(Math.max(0, workers));
            api = loaded;
            context = rendererContext;
            System.out.println("[Native Accelerator] Renderer backend: " + loaded.backendName());
            System.out.println("[Native Accelerator] Vulkan loader: " + loaded.vulkanLoaderName()
                    + " api=0x" + Integer.toHexString(loaded.vulkanLoaderApiVersion())
                    + " devices=" + deviceCount);
            System.out.println("[Native Accelerator] Renderer capabilities: " + RendererCapabilities.names(loaded.capabilities()));
            System.out.println("[Native Accelerator] Renderer workers: " + loaded.contextWorkerCount(rendererContext));
        } catch (Throwable t) {
            api = null;
            context = MemorySegment.NULL;
            System.err.println("[Native Accelerator] Vulkan renderer unavailable; default renderer remains active: " + t.getMessage());
        }
    }

    public static Optional<RendererNativeApi> api() {
        return Optional.ofNullable(api);
    }

    public static boolean active() {
        return api != null && !context.equals(MemorySegment.NULL);
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
        RendererNativeApi loaded = api;
        if (loaded == null) throw new IllegalStateException("Native Vulkan renderer is not active");
        return loaded;
    }
}
