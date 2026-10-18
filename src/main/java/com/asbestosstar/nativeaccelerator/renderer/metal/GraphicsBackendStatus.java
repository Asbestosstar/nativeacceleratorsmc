package com.asbestosstar.nativeaccelerator.renderer.metal;

/** Status used by the selector to explain a request without conflating it with the active device. */
public record GraphicsBackendStatus(GraphicsApiChoice choice, State state, String reason) {
    public enum State { SUPPORTED, BLOCKED, FALLBACK, UNKNOWN }

    public GraphicsBackendStatus {
        state = state == null ? State.UNKNOWN : state;
        reason = reason == null ? "" : reason;
    }
}
