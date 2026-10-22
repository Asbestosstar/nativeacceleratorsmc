package com.asbestosstar.nativeaccelerator.renderer;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;

/** Diagnostics and throwable normalization at Minecraft's backend-attempt boundary. */
public final class BackendAttemptDiagnostics {
    private BackendAttemptDiagnostics() {}

    public static void report(GpuBackend backend, Throwable failure) {
        if (backend == null || failure == null) return;
        System.err.println(format(backend.getName(), failure));
    }

    public static String format(String backendName, Throwable failure) {
        String backend = backendName == null || backendName.isBlank() ? "unknown" : backendName.trim();
        String type = failure == null ? "<none>" : failure.getClass().getName();
        String message = failure == null ? "<none>" : safeMessage(failure);
        return "[Native Accelerator] graphics backend attempt failed: backend=" + backend
                + " exception=" + type + " message=\"" + message + "\"";
    }

    /** Preserve RenderPearl's authoritative exception; normalize only throwable failures. */
    public static BackendCreationException normalize(String backendName, Throwable failure) {
        if (failure instanceof BackendCreationException backendFailure) return backendFailure;
        BackendCreationException.Reason reason = "OpenGL".equalsIgnoreCase(backendName)
                ? BackendCreationException.Reason.OPENGL_MISSING
                : BackendCreationException.Reason.PLATFORM_ERROR;
        return new BackendCreationException(
                "Graphics backend " + (backendName == null || backendName.isBlank() ? "unknown" : backendName)
                        + " failed with " + failure.getClass().getSimpleName() + ": " + safeMessage(failure),
                reason);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return "<none>";
        return message.replace('"', '\'').replace('\r', ' ').replace('\n', ' ').trim();
    }
}
