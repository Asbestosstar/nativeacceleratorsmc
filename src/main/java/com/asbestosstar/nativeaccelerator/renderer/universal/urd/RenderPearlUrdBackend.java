package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.mojang.renderpearl.api.device.GpuDevice;

/** Backend adapter for a RenderPearl device that Minecraft has already created. */
public class RenderPearlUrdBackend implements UrdBackend {
    private final String name;
    private final GpuDevice device;

    public RenderPearlUrdBackend(String name, GpuDevice device) {
        this.name = name == null || name.isBlank() ? "renderpearl" : name;
        this.device = device;
    }

    @Override public String name() { return name; }
    @Override public UrdBackendOwnership ownership() { return UrdBackendOwnership.RENDERPEARL; }
    @Override public UrdCapabilities capabilities() {
        if (device == null) throw new UrdValidationException("backend has no supplied GPU device");
        try (RenderPearlUrdDevice adapter = new RenderPearlUrdDevice(device, name)) {
            return adapter.capabilities();
        }
    }
    @Override public UrdDevice createDevice() {
        if (device == null) throw new UrdValidationException("backend has no supplied GPU device");
        return new RenderPearlUrdDevice(device, name);
    }
}
