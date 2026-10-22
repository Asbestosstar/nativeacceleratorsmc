package com.asbestosstar.nativeaccelerator.renderer;

import com.mojang.renderpearl.api.device.BackendCreationException;

public final class BackendAttemptDiagnosticsTest {
    public static void main(String[] args) {
        RuntimeException glx = new RuntimeException("GLXBadFBConfig: no matching FBConfig\nSDL_GL_CreateContext");
        String formatted = BackendAttemptDiagnostics.format("OpenGL", glx);
        require(formatted.contains("backend=OpenGL"), formatted);
        require(formatted.contains("GLXBadFBConfig: no matching FBConfig SDL_GL_CreateContext"), formatted);
        require(!formatted.contains("\n"), formatted);

        BackendCreationException original = new BackendCreationException(
                "Vulkan requirements unavailable", BackendCreationException.Reason.VULKAN_MISSING_FEATURE);
        require(BackendAttemptDiagnostics.normalize("Vulkan", original) == original, "exception was replaced");

        BackendCreationException converted = BackendAttemptDiagnostics.normalize("OpenGL", glx);
        require(converted.getReason() == BackendCreationException.Reason.OPENGL_MISSING,
                converted.getReason().toString());
        require(converted.getMessage().contains("GLXBadFBConfig"), converted.getMessage());

        BackendCreationException generic = BackendAttemptDiagnostics.normalize("Other", new LinkageError("missing symbol"));
        require(generic.getReason() == BackendCreationException.Reason.PLATFORM_ERROR,
                generic.getReason().toString());
        System.out.println("BackendAttemptDiagnosticsTest: PASS");
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
