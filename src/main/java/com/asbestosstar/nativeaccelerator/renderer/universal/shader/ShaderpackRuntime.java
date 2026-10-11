package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe publication boundary for compiled shaderpack generations.
 *
 * <p>Compilation and backend resource creation happen outside this class. Callers publish only a
 * complete immutable generation; failed preparation cannot partially alter the active generation.</p>
 */
public final class ShaderpackRuntime {
    public enum State { DISABLED, PREPARING, ACTIVE, FAILED, CLOSED }

    private final AtomicReference<ShaderpackGeneration> active = new AtomicReference<>();
    private final AtomicReference<ShaderpackGeneration> previous = new AtomicReference<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.DISABLED);
    private final AtomicReference<List<String>> diagnostics = new AtomicReference<>(List.of());

    public State state() { return state.get(); }
    public ShaderpackGeneration active() { return active.get(); }
    public ShaderpackGeneration previous() { return previous.get(); }
    public List<String> diagnostics() { return diagnostics.get(); }

    public void beginPreparation() {
        ensureOpen();
        state.set(State.PREPARING);
    }

    /** Publishes a complete usable generation and retires the former generation logically. */
    public void activate(ShaderpackGeneration generation) {
        Objects.requireNonNull(generation, "generation");
        ensureOpen();
        if (!generation.executable()) {
            fail(generation.diagnostics().isEmpty()
                    ? List.of("generation is not executable: " + generation.outcome())
                    : generation.diagnostics());
            return;
        }
        ShaderpackGeneration old = active.getAndSet(generation);
        if (old != null) previous.set(old);
        diagnostics.set(generation.diagnostics());
        state.set(State.ACTIVE);
    }

    /** Keeps the current generation active while reporting a failed replacement. */
    public void fail(List<String> reasons) {
        ensureOpen();
        diagnostics.set(List.copyOf(reasons == null ? List.of("shaderpack preparation failed") : reasons));
        state.set(active.get() == null ? State.FAILED : State.ACTIVE);
    }

    /** Records a blocked preparation without pretending that a backend failure is a syntax error. */
    public void block(String reason) {
        ensureOpen();
        diagnostics.set(List.of(reason == null || reason.isBlank() ? "shaderpack blocked" : reason));
        state.set(active.get() == null ? State.FAILED : State.ACTIVE);
    }

    public void disable(String reason) {
        ensureOpen();
        ShaderpackGeneration old = active.getAndSet(null);
        if (old != null) previous.set(old);
        diagnostics.set(List.of(reason == null || reason.isBlank() ? "shaderpack disabled" : reason));
        state.set(State.DISABLED);
    }

    public void close() {
        active.set(null);
        previous.set(null);
        diagnostics.set(List.of());
        state.set(State.CLOSED);
    }

    private void ensureOpen() {
        if (state.get() == State.CLOSED) throw new IllegalStateException("shaderpack runtime is closed");
    }
}
