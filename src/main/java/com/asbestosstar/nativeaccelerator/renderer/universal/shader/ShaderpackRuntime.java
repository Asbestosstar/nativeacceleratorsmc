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
    public enum AttemptState { NOT_ATTEMPTED, PREPARING, ACTIVE, FAILED }
    public record AttemptSnapshot(String packId, AttemptState state, List<String> diagnostics) {
        public AttemptSnapshot {
            packId = packId == null ? "" : packId;
            Objects.requireNonNull(state, "state");
            diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        }
    }

    private final AtomicReference<ShaderpackGeneration> active = new AtomicReference<>();
    private final AtomicReference<ShaderpackGeneration> previous = new AtomicReference<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.DISABLED);
    private final AtomicReference<List<String>> diagnostics = new AtomicReference<>(List.of());
    private final AtomicReference<AttemptSnapshot> attempt = new AtomicReference<>(
            new AttemptSnapshot("", AttemptState.NOT_ATTEMPTED, List.of()));

    public State state() { return state.get(); }
    public AttemptSnapshot attemptSnapshot() { return attempt.get(); }
    public ShaderpackGeneration active() { return active.get(); }
    public ShaderpackGeneration previous() { return previous.get(); }
    public List<String> diagnostics() { return diagnostics.get(); }

    public void beginPreparation() {
        beginPreparation("");
    }

    public void beginPreparation(String packId) {
        ensureOpen();
        ShaderpackGeneration old = active.getAndSet(null);
        if (old != null) previous.set(old);
        state.set(State.PREPARING);
        attempt.set(new AttemptSnapshot(packId, AttemptState.PREPARING, List.of()));
    }

    /** Publishes a complete usable generation and retires the former generation logically. */
    public void activate(ShaderpackGeneration generation) {
        Objects.requireNonNull(generation, "generation");
        ensureOpen();
        if (!attempt.get().packId().isBlank() && !attempt.get().packId().equals(generation.packId())) {
            throw new IllegalArgumentException("generation does not match the current shaderpack attempt");
        }
        if (!generation.executable()) {
            fail(generation.packId(), generation.diagnostics().isEmpty()
                    ? List.of("generation is not executable: " + generation.outcome())
                    : generation.diagnostics());
            return;
        }
        ShaderpackGeneration old = active.getAndSet(generation);
        if (old != null) previous.set(old);
        diagnostics.set(generation.diagnostics());
        state.set(State.ACTIVE);
        attempt.set(new AttemptSnapshot(generation.packId(), AttemptState.ACTIVE, generation.diagnostics()));
    }

    /** Keeps the current generation active while publishing a failure for the attempted replacement. */
    public void fail(List<String> reasons) {
        fail(attempt.get().packId(), reasons);
    }

    public void fail(String packId, List<String> reasons) {
        ensureOpen();
        List<String> detail = List.copyOf(reasons == null ? List.of("shaderpack preparation failed") : reasons);
        diagnostics.set(detail);
        active.set(null);
        state.set(State.FAILED);
        attempt.set(new AttemptSnapshot(packId, AttemptState.FAILED, detail));
    }

    /** Records a blocked preparation without pretending that a backend failure is a syntax error. */
    public void block(String reason) {
        block(attempt.get().packId(), reason);
    }

    public void block(String packId, String reason) {
        ensureOpen();
        List<String> detail = List.of(reason == null || reason.isBlank() ? "shaderpack blocked" : reason);
        diagnostics.set(detail);
        active.set(null);
        state.set(State.FAILED);
        attempt.set(new AttemptSnapshot(packId, AttemptState.FAILED, detail));
    }

    public void disable(String reason) {
        ensureOpen();
        ShaderpackGeneration old = active.getAndSet(null);
        if (old != null) previous.set(old);
        diagnostics.set(List.of(reason == null || reason.isBlank() ? "shaderpack disabled" : reason));
        state.set(State.DISABLED);
        attempt.set(new AttemptSnapshot("", AttemptState.NOT_ATTEMPTED, List.of()));
    }

    public void close() {
        active.set(null);
        previous.set(null);
        diagnostics.set(List.of());
        attempt.set(new AttemptSnapshot("", AttemptState.NOT_ATTEMPTED, List.of()));
        state.set(State.CLOSED);
    }

    private void ensureOpen() {
        if (state.get() == State.CLOSED) throw new IllegalStateException("shaderpack runtime is closed");
    }
}
