package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

public interface UrdBackend {
    String name();
    UrdCapabilities capabilities();
    UrdDevice createDevice();

    /** Ownership of the underlying device/context, used to prevent duplicate Vulkan ownership. */
    default UrdBackendOwnership ownership() {
        return UrdBackendOwnership.REFERENCE;
    }
}
