package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.asbestosstar.nativeaccelerator.platform.ProcessInitializationGuard;

/**
 * Process-wide ownership gate for the future client-facing Vulkan adapter.
 *
 * <p>RenderPearl may discover the same universal JAR through more than one loader/classloader. A
 * normal static boolean would permit two device attempts in that situation. The claim therefore uses
 * the shared JVM-property guard. A failed attempt can release its claim; a committed device cannot.</p>
 */
public final class NativeVulkanOwnership {
    private static final String CLAIM = "nativeaccelerator.renderer.nativeBackend.device";
    private static final String COMMITTED = CLAIM + ".committed";

    private NativeVulkanOwnership() {
    }

    public static boolean tryClaim() {
        return ProcessInitializationGuard.claim(CLAIM);
    }

    /**
     * Claims the backend and returns the exact process-wide token owned by this attempt, or null when
     * another loader already owns it. The token must be passed to {@link #releaseFailedAttempt(String)}.
     */
    public static String tryClaimToken() {
        return tryClaim() ? ProcessInitializationGuard.claimDescription(CLAIM) : null;
    }

    public static boolean claimed() {
        return ProcessInitializationGuard.claimed(CLAIM);
    }

    public static boolean committed() {
        return ProcessInitializationGuard.claimed(COMMITTED);
    }

    /** Atomically commits this exact claim token. */
    public static boolean commit(String token) {
        return ProcessInitializationGuard.commitIfValue(CLAIM, token, COMMITTED);
    }

    /** Compatibility form for existing tests and single-threaded callers. */
    public static void commit() {
        String token = ProcessInitializationGuard.claimDescription(CLAIM);
        if (!commit(token)) throw new IllegalStateException("Native Vulkan ownership was not claimed");
    }

    /** Release only a failed, uncommitted attempt (legacy/test form). */
    public static void releaseFailedAttempt() {
        if (committed()) throw new IllegalStateException("Cannot release committed Native Vulkan ownership");
        ProcessInitializationGuard.release(CLAIM);
    }

    /** Release only this attempt's still-current, uncommitted claim. */
    public static boolean releaseFailedAttempt(String token) {
        if (committed()) throw new IllegalStateException("Cannot release committed Native Vulkan ownership");
        return ProcessInitializationGuard.releaseIfValue(CLAIM, token);
    }
}
