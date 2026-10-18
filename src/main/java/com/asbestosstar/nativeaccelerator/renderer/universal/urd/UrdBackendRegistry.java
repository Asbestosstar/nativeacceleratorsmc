package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;
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
        if (activeNativeDevice == device && activeKind == kind && activeBackend != null && activeDevice != null) {
            return new UrdBackendSelection(UrdBackendSelection.Status.DUPLICATE, kind,
                    activeBackend, activeDevice, activeDevice.capabilities(), "device was already registered");
        }
        if (activeNativeDevice != null && activeNativeDevice != device) {
            selection = UrdBackendSelection.blocked(kind,
                    "a different Minecraft GPU device is already registered; close the old client before replacing it");
            return selection;
        }

        UrdBackend adapter = new RenderPearlUrdBackend(kind.name().toLowerCase(java.util.Locale.ROOT), device);
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
        selection = new UrdBackendSelection(UrdBackendSelection.Status.ACTIVE, kind,
                adapter, urdDevice, urdDevice.capabilities(), "");
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
        activeKind = RendererBackendRuntime.Backend.NONE;
        selection = UrdBackendSelection.blocked(activeKind, "registry cleared");
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
