package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

public interface UrdBackend {
    String name();
    UrdCapabilities capabilities();
    UrdDevice createDevice();
}
