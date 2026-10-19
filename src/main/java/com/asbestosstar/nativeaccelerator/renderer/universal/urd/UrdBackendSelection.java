package com.asbestosstar.nativeaccelerator.renderer.universal.urd;

import com.asbestosstar.nativeaccelerator.renderer.RendererBackendRuntime;

/** Immutable result of registering the device Minecraft actually created. */
public record UrdBackendSelection(
        Status status,
        RendererBackendRuntime.Backend backend,
        UrdBackend adapter,
        UrdDevice device,
        UrdCapabilities capabilities,
        UrdBackendOwnership ownership,
        String reason) {
    public enum Status {
        ACTIVE,
        DUPLICATE,
        BLOCKED
    }

    public UrdBackendSelection {
        status = status == null ? Status.BLOCKED : status;
        backend = backend == null ? RendererBackendRuntime.Backend.NONE : backend;
        ownership = ownership == null ? UrdBackendOwnership.REFERENCE : ownership;
        reason = reason == null ? "" : reason;
    }

    public boolean active() {
        return status == Status.ACTIVE || status == Status.DUPLICATE;
    }

    public static UrdBackendSelection blocked(RendererBackendRuntime.Backend backend, String reason) {
        return new UrdBackendSelection(Status.BLOCKED, backend, null, null, null,
                UrdBackendOwnership.REFERENCE, reason);
    }
}
