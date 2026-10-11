package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.Objects;

/** Result for one requested shader semantic. */
public record ShaderpackCapabilityResult(
        ShaderpackCapability capability,
        ShaderpackCapabilityState state,
        String detail) {

    public ShaderpackCapabilityResult {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(state, "state");
        detail = detail == null ? "" : detail.trim();
    }

    public ShaderpackOutcome outcome() {
        return state.outcome();
    }

    public boolean acceptedByDefault() {
        return outcome().acceptedByDefault();
    }
}
