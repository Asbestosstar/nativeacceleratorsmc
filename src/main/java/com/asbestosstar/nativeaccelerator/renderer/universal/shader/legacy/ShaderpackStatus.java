package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

/** Truthful catalog state; discovery never implies that a pack can execute. */
public enum ShaderpackStatus {
    /** Assigned only after a complete backend-specific generation is prepared and published. */
    EXECUTABLE,
    FRONTEND_ONLY,
    INCOMPLETE,
    UNSUPPORTED,
    INVALID,
    /** The pack could not be prepared on this host, driver, or security budget. */
    BLOCKED,
    UNKNOWN;

    public boolean executable() {
        return this == EXECUTABLE;
    }
}
