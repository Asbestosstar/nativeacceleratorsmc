package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.InMemoryUrdBackend;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdBackendRegistry;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdBackendSelection;

/** Dependency-light checks for process-wide URD registration semantics. */
public final class UrdBackendRegistryTest {
    public static void main(String[] args) {
        UrdBackendRegistry registry = UrdBackendRegistry.global();
        registry.clear();

        UrdBackendSelection unsupported = registry.register(RendererBackendRuntime.Backend.OTHER, null);
        if (unsupported.status() != UrdBackendSelection.Status.BLOCKED) {
            throw new AssertionError("unsupported backend was accepted");
        }

        // The global registry deliberately wraps only RenderPearl devices. Keep the reference backend
        // available for pure tests without registering it as a Minecraft device.
        try (var reference = new InMemoryUrdBackend().createDevice()) {
            if (!reference.capabilities().supports("triangle")) throw new AssertionError("reference backend");
        }
        registry.clear();
        System.out.println("UrdBackendRegistryTest: PASS");
    }
}
