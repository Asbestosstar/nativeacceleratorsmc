package com.asbestosstar.nativeaccelerator.renderer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fail-open observation/dispatch seam around Minecraft's LevelRenderer frame construction.
 *
 * <p>This deliberately does not submit passes, execute a graph, or replace Minecraft rendering.
 * A future renderer integration may register an observer for the verified lifecycle seam; until
 * then the default is a no-op and vanilla owns the complete frame graph.</p>
 */
public final class LevelFrameGraphDispatch {
    public enum Phase {
        FRAME_RENDER_BEGIN,
        FRAME_RENDER_END
    }

    @FunctionalInterface
    public interface Hook {
        void onFrame(Phase phase, Object levelRenderer, Object levelRenderState, Object submitNodeStorage);
    }

    private static final Hook NO_OP = (phase, renderer, state, storage) -> {};
    private static final AtomicReference<Hook> HOOK = new AtomicReference<>(NO_OP);

    private LevelFrameGraphDispatch() {}

    /** Install an observer; passing {@code null} restores the no-op default. */
    public static void setHook(Hook hook) {
        HOOK.set(Objects.requireNonNullElse(hook, NO_OP));
    }

    /** Invoke the optional observer while ensuring third-party dispatch cannot break vanilla. */
    public static void dispatch(Phase phase, Object levelRenderer, Object levelRenderState, Object submitNodeStorage) {
        try {
            HOOK.get().onFrame(phase, levelRenderer, levelRenderState, submitNodeStorage);
        } catch (Throwable failure) {
            System.err.println("[Native Accelerator] Level frame-graph observer failed open: " + failure);
        }
    }
}
