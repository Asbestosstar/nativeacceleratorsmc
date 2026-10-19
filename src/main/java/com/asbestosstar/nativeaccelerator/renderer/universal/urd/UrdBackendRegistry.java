package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;
import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;
import com.mojang.renderpearl.api.device.GpuDevice;

/**
 * Process-local registry for the one Minecraft-created graphics device exposed to URD.
 *
 * <p>The registry owns only the URD wrapper. Minecraft remains the owner of the supplied
 * {@link GpuDevice}; closing or replacing a wrapper never closes that device.</p>
 */
public final class UrdBackendRegistry {
    private static final UrdBackendRegistry INSTANCE = new UrdBackendRegistry();

    private RendererBackendRuntime.Backend activeKind = RendererBackendRuntime.Backend.NONE;
    private GpuDevice activeNativeDevice;
    private UrdBackend activeBackend;
    private UrdDevice activeDevice;
    private UrdBackendOwnership activeOwnership = UrdBackendOwnership.REFERENCE;
    private UrdBackendSelection selection = UrdBackendSelection.blocked(
            RendererBackendRuntime.Backend.NONE, "no Minecraft graphics device registered");

    private UrdBackendRegistry() {
    }

    public static UrdBackendRegistry global() {
        return INSTANCE;
    }

    public synchronized UrdBackendSelection register(RendererBackendRuntime.Backend kind, GpuDevice device) {
        if (kind == null) {
            selection = UrdBackendSelection.blocked(RendererBackendRuntime.Backend.NONE, "backend identity was null");
            return selection;
        }
        if (device == null) {
            selection = UrdBackendSelection.blocked(kind, "Minecraft supplied no GPU device");
            return selection;
        }
        if (kind == RendererBackendRuntime.Backend.NONE || kind == RendererBackendRuntime.Backend.OTHER) {
            selection = UrdBackendSelection.blocked(kind, "backend is not a supported URD graphics backend");
            return selection;
        }
        // Ownership guard: a native Vulkan session already holds the registry; the caller must
        // clear() before switching to a RenderPearl-owned device.
        if (activeOwnership == UrdBackendOwnership.NATIVE_VULKAN && activeBackend != null) {
            selection = UrdBackendSelection.blocked(kind,
                    "a native Vulkan URD session is already registered; call clear() before registering a RenderPearl device");
            return selection;
        }
        if (activeNativeDevice == device && activeKind == kind && activeBackend != null && activeDevice != null) {
            return new UrdBackendSelection(UrdBackendSelection.Status.DUPLICATE, kind,
                    activeBackend, activeDevice, activeDevice.capabilities(),
                    activeOwnership, "device was already registered");
        }
        if (activeNativeDevice != null && activeNativeDevice != device) {
            selection = UrdBackendSelection.blocked(kind,
                    "a different Minecraft GPU device is already registered; close the old client before replacing it");
            return selection;
        }

        UrdBackend adapter = new RenderPearlUrdBackend(kind.name().toLowerCase(java.util.Locale.ROOT), device);
        UrdBackendOwnership ownership = adapter.ownership();
        UrdDevice urdDevice;
        try {
            urdDevice = adapter.createDevice();
        } catch (RuntimeException failure) {
            selection = UrdBackendSelection.blocked(kind,
                    "unable to wrap Minecraft GPU device: " + message(failure));
            return selection;
        }
        activeKind = kind;
        activeNativeDevice = device;
        activeBackend = adapter;
        activeDevice = urdDevice;
        activeOwnership = ownership;
        selection = new UrdBackendSelection(UrdBackendSelection.Status.ACTIVE, kind,
                adapter, urdDevice, urdDevice.capabilities(), ownership, "");
        return selection;
    }

    /**
     * Registers a Native Accelerator Vulkan session as the active URD backend.
     *
     * <p>This is an opt-in path for callers that already hold an active
     * {@link com.asbestosstar.nativeaccelerator.renderer.NativeVulkanRenderer} and want URD to
     * use its Vulkan session rather than wrapping a RenderPearl-owned device. No
     * {@link GpuDevice} is required: the native session owns the Vulkan context.</p>
     *
     * <p>The method performs three pre-flight checks before attempting session creation:
     * <ol>
     *   <li>A RenderPearl-owned device must not already be registered.</li>
     *   <li>The Vulkan session ABI must be present in the loaded library.</li>
     *   <li>A Vulkan loader and at least one physical device must be available.</li>
     * </ol>
     * Any failure returns a {@link UrdBackendSelection} with status {@code BLOCKED}; the
     * selection is never thrown. A duplicate call for an already-active native Vulkan session
     * returns status {@code DUPLICATE}.</p>
     *
     * @param api a non-null, active {@link RendererNativeApi} instance
     * @return the resulting selection; never null
     */
    public synchronized UrdBackendSelection registerNativeVulkan(RendererNativeApi api) {
        if (api == null) {
            selection = UrdBackendSelection.blocked(RendererBackendRuntime.Backend.NONE,
                    "null RendererNativeApi supplied to registerNativeVulkan");
            return selection;
        }
        // Ownership guard: a RenderPearl device already occupies the registry.
        if (activeOwnership == UrdBackendOwnership.RENDERPEARL && activeBackend != null) {
            selection = UrdBackendSelection.blocked(RendererBackendRuntime.Backend.VULKAN,
                    "a RenderPearl-owned URD device is already registered; "
                            + "call clear() before registering a native Vulkan session");
            return selection;
        }
        // Duplicate guard: native Vulkan session already active.
        if (activeOwnership == UrdBackendOwnership.NATIVE_VULKAN
                && activeBackend != null && activeDevice != null) {
            return new UrdBackendSelection(UrdBackendSelection.Status.DUPLICATE,
                    RendererBackendRuntime.Backend.VULKAN,
                    activeBackend, activeDevice, activeDevice.capabilities(),
                    activeOwnership, "native Vulkan session was already registered");
        }
        // Pre-flight: session ABI must be present before we try to create a session.
        if (!api.vulkanSessionSupported()) {
            selection = UrdBackendSelection.blocked(RendererBackendRuntime.Backend.VULKAN,
                    "Vulkan session ABI not present in this library build");
            return selection;
        }
        // Pre-flight: require a loader and at least one physical device.
        if (!api.vulkanLoaderAvailable() || api.vulkanPhysicalDeviceCount() == 0) {
            selection = UrdBackendSelection.blocked(RendererBackendRuntime.Backend.VULKAN,
                    "no Vulkan loader or physical device available on this machine");
            return selection;
        }

        NativeVulkanUrdBackend adapter = new NativeVulkanUrdBackend(
                api, VulkanCapabilityTier.BASIC, "");
        UrdDevice urdDevice;
        try {
            urdDevice = adapter.createDevice();
        } catch (RuntimeException failure) {
            selection = UrdBackendSelection.blocked(RendererBackendRuntime.Backend.VULKAN,
                    "unable to create native Vulkan URD session: " + message(failure));
            return selection;
        }
        activeKind = RendererBackendRuntime.Backend.VULKAN;
        activeNativeDevice = null; // native session owns the Vulkan context; no GpuDevice involved
        activeBackend = adapter;
        activeDevice = urdDevice;
        activeOwnership = UrdBackendOwnership.NATIVE_VULKAN;
        selection = new UrdBackendSelection(UrdBackendSelection.Status.ACTIVE,
                RendererBackendRuntime.Backend.VULKAN,
                adapter, urdDevice, urdDevice.capabilities(),
                UrdBackendOwnership.NATIVE_VULKAN, "");
        return selection;
    }

    public synchronized UrdBackendSelection selection() {
        return selection;
    }

    public synchronized UrdBackend backend() {
        return activeBackend;
    }

    public synchronized UrdDevice device() {
        return activeDevice;
    }

    public synchronized RendererBackendRuntime.Backend activeKind() {
        return activeKind;
    }

    public synchronized UrdBackendOwnership activeOwnership() {
        return activeOwnership;
    }

    /** Clears the URD wrapper while deliberately leaving the Minecraft-owned device untouched. */
    public synchronized void clear() {
        if (activeDevice != null) {
            try {
                activeDevice.close();
            } catch (RuntimeException ignored) {
                // A failed adapter cleanup must not prevent the next Minecraft client from starting.
            }
        }
        activeDevice = null;
        activeBackend = null;
        activeNativeDevice = null;
        activeOwnership = UrdBackendOwnership.REFERENCE;
        activeKind = RendererBackendRuntime.Backend.NONE;
        selection = UrdBackendSelection.blocked(activeKind, "registry cleared");
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
