package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

/** How a renderer supplies one shader semantic. */
public enum ShaderpackCapabilityState {
    /** The backend exposes the semantic directly. */
    NATIVE,
    /** The backend can lower the semantic without changing its defined behavior. */
    TRANSLATED,
    /** The renderer can preserve it using additional passes or services. */
    EMULATED,
    /** The semantic can only be used when approximation has been explicitly enabled. */
    APPROXIMATED,
    /** The semantic is not available. */
    UNAVAILABLE;

    ShaderpackOutcome outcome() {
        return switch (this) {
            case NATIVE -> ShaderpackOutcome.NATIVE;
            case TRANSLATED -> ShaderpackOutcome.EXACT_TRANSLATION;
            case EMULATED -> ShaderpackOutcome.SEMANTIC_EMULATION;
            case APPROXIMATED -> ShaderpackOutcome.APPROXIMATION_OPT_IN;
            case UNAVAILABLE -> ShaderpackOutcome.UNSUPPORTED;
        };
    }
}
