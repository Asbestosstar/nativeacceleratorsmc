package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

/** Fidelity outcome for a shaderpack/backend negotiation. */
public enum ShaderpackOutcome {
    /** The requested shader semantics execute directly on the target backend. */
    NATIVE,
    /** A lowering preserves the defined behavior within the backend's documented tolerance. */
    EXACT_TRANSLATION,
    /** Extra passes, textures, or renderer services preserve the requested semantics. */
    SEMANTIC_EMULATION,
    /** Fidelity is reduced and the user explicitly opted into the approximation. */
    APPROXIMATION_OPT_IN,
    /** The profile cannot be executed faithfully with the current capabilities. */
    UNSUPPORTED,
    /** Preparation could not be proven because a compiler, device, or runtime prerequisite is absent. */
    BLOCKED,
    /** Source or declarations violate the supported input contract. */
    INVALID,
    /** Source preparation succeeded but no complete execution adapter is available. */
    FRONTEND_ONLY;

    /** Whether this outcome is usable without an explicit approximation opt-in. */
    public boolean acceptedByDefault() {
        return this != UNSUPPORTED && this != BLOCKED && this != INVALID
                && this != FRONTEND_ONLY && this != APPROXIMATION_OPT_IN;
    }
}
