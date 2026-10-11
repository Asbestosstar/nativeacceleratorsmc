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
    UNSUPPORTED;

    /** Whether this outcome is usable without an explicit approximation opt-in. */
    public boolean acceptedByDefault() {
        return this != UNSUPPORTED && this != APPROXIMATION_OPT_IN;
    }
}
