package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/**
 * Backend-neutral recording lifecycle shared by command-list implementations.
 *
 * <p>This class deliberately has no Vulkan, RenderPearl, Minecraft, foreign-memory, or native
 * dependency. It only decides whether a recording operation is legal in the current state. The
 * wrapper still owns resource identity, ownership, and every native/engine call, and must invoke the
 * corresponding transition ({@link #enterPass()}, {@link #markPipelineBound()}, ...) only after the
 * backend operation actually succeeded. Consequently this class proves nothing about GPU
 * availability: a valid transition sequence says only that the caller used the command list
 * correctly.</p>
 *
 * <p>Two submission conventions exist in the existing backends: a backend that submits in
 * {@code submit()} calls {@link #submit()}; a backend whose offscreen contract submits inside
 * {@code endPass()} calls {@link #markSubmittedInsideEndPass()} instead and then treats a later
 * {@link #submit()} as the contract-required no-op. Both conventions reject recording after
 * submission and reject a second real submission.</p>
 *
 * <p>Exception rule: structurally illegal use (wrong phase, missing prerequisite) raises
 * {@link IllegalStateException}; an intrinsically meaningless numeric argument raises
 * {@link IllegalArgumentException}. An offset that exceeds a known resource size is reported as an
 * {@link IllegalStateException} because it depends on bound-resource state rather than on the
 * number alone. A caller that needs the URD exception type can catch
 * {@link IllegalStateException} (URD's validation exception is a subtype); substitution of a
 * backend-specific exception type is deliberately left to the wrapper.</p>
 */
public final class NativeVulkanCommandState {
    public enum Phase {
        /** Open, no pass active, nothing submitted yet. */
        RECORDING,
        /** A render pass is active. */
        IN_PASS,
        /** Submission has already been issued (or performed by {@code endPass}). */
        SUBMITTED,
        /** Closed; no further operation is legal. */
        CLOSED
    }

    private Phase phase = Phase.RECORDING;
    private boolean pipelineBound;
    private boolean vertexBufferBound;
    private boolean submittedInsideEndPass;

    public Phase phase() {
        return phase;
    }

    public boolean isClosed() {
        return phase == Phase.CLOSED;
    }

    public boolean inPass() {
        return phase == Phase.IN_PASS;
    }

    public boolean isSubmitted() {
        return phase == Phase.SUBMITTED;
    }

    public boolean pipelineBound() {
        return pipelineBound;
    }

    public boolean vertexBufferBound() {
        return vertexBufferBound;
    }

    public void requireOpen() {
        if (phase == Phase.CLOSED) {
            throw new IllegalStateException("command list is closed");
        }
    }

    /** Validates {@code beginPass} without changing state. */
    public void requireCanBeginPass(String name, Object colorTarget) {
        requireOpen();
        if (phase == Phase.IN_PASS) {
            throw new IllegalStateException("nested render pass");
        }
        if (phase == Phase.SUBMITTED) {
            throw new IllegalStateException("command list already submitted");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("blank pass name");
        }
        if (colorTarget == null) {
            throw new IllegalStateException("null color target");
        }
    }

    /** Enters a pass after the backend successfully opened it. */
    public void enterPass() {
        requireOpen();
        if (phase != Phase.RECORDING) {
            throw new IllegalStateException("cannot enter render pass from " + phase);
        }
        phase = Phase.IN_PASS;
        pipelineBound = false;
        vertexBufferBound = false;
    }

    /** Validates a pipeline bind without marking it bound. */
    public void requireCanSetPipeline(Object pipeline) {
        requireOpen();
        if (phase != Phase.IN_PASS || pipeline == null) {
            throw new IllegalStateException("pipeline must be set inside a pass");
        }
    }

    /** Marks the pipeline bound after the backend bind succeeded. */
    public void markPipelineBound() {
        requireInPass("pipeline bind");
        pipelineBound = true;
    }

    /**
     * Validates a vertex-buffer bind without marking it bound.
     *
     * @param slot       binding slot to validate
     * @param buffer     non-null buffer reference (identity is the wrapper's concern)
     * @param offset     byte offset inside the buffer
     * @param bufferSize size reported by the buffer wrapper
     * @param maxSlots   exclusive upper bound on {@code slot} for this backend
     */
    public void requireCanSetVertexBuffer(int slot, Object buffer, long offset, long bufferSize, int maxSlots) {
        requireOpen();
        if (phase != Phase.IN_PASS) {
            throw new IllegalStateException("vertex buffer binding outside render pass");
        }
        if (slot < 0 || slot >= maxSlots) {
            throw new IllegalStateException("invalid vertex buffer slot: " + slot);
        }
        if (buffer == null) {
            throw new IllegalStateException("null vertex buffer");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("invalid vertex buffer offset: " + offset);
        }
        if (offset >= bufferSize) {
            throw new IllegalStateException(
                    "vertex buffer offset " + offset + " >= buffer size " + bufferSize);
        }
    }

    /** Marks a vertex buffer bound after the backend bind succeeded. */
    public void markVertexBufferBound() {
        requireInPass("vertex buffer bind");
        vertexBufferBound = true;
    }

    public void drawIndexed(int indexCount, int firstIndex) {
        requireOpen();
        if (phase != Phase.IN_PASS) {
            throw new IllegalStateException("drawIndexed outside render pass");
        }
        if (!pipelineBound || !vertexBufferBound) {
            throw new IllegalStateException("indexed draw state is incomplete");
        }
        if (indexCount <= 0) {
            throw new IllegalArgumentException("indexCount must be positive");
        }
        if (firstIndex < 0) {
            throw new IllegalArgumentException("firstIndex must be >= 0");
        }
    }

    /** Validates an index-buffer byte range without overflowing. */
    public static void validateIndexRange(long bufferSize, int firstIndex, int indexCount, int indexBytes) {
        if (bufferSize < 0 || firstIndex < 0 || indexCount <= 0 || (indexBytes != 2 && indexBytes != 4)) {
            throw new IllegalArgumentException("invalid indexed draw range");
        }
        long end = Math.multiplyExact(Math.addExact((long) firstIndex, indexCount), indexBytes);
        if (end > bufferSize) {
            throw new IllegalStateException("drawIndexed index range exceeds bound index buffer");
        }
    }

    /** Validates a draw; never mutates state because drawing does not change the recording phase. */
    public void draw(int vertexCount, int firstVertex) {
        requireOpen();
        if (phase != Phase.IN_PASS) {
            throw new IllegalStateException("draw outside render pass");
        }
        if (!pipelineBound || !vertexBufferBound) {
            throw new IllegalStateException("draw state is incomplete");
        }
        if (vertexCount <= 0) {
            throw new IllegalArgumentException("vertexCount must be positive");
        }
        if (firstVertex < 0) {
            throw new IllegalArgumentException("firstVertex must be >= 0");
        }
    }

    /** Validates {@code endPass} without leaving the pass. */
    public void requireCanEndPass() {
        requireOpen();
        if (phase != Phase.IN_PASS) {
            throw new IllegalStateException("no active render pass");
        }
    }

    /** Leaves the pass after the backend successfully closed it. */
    public void leavePass() {
        requireCanEndPass();
        phase = Phase.RECORDING;
        pipelineBound = false;
        vertexBufferBound = false;
    }

    /**
     * Final submission for backends that submit from {@code submit()}.
     *
     * <p>If this backend already submitted inside {@code endPass}, the call is the contract-required
     * no-op and may be repeated. Otherwise a second submission is rejected.</p>
     */
    public void submit() {
        requireOpen();
        if (phase == Phase.IN_PASS) {
            throw new IllegalStateException("submit inside render pass");
        }
        if (phase == Phase.SUBMITTED) {
            if (submittedInsideEndPass) {
                return;
            }
            throw new IllegalStateException("command list already submitted");
        }
        phase = Phase.SUBMITTED;
    }

    /**
     * Records that the backend submitted during {@code endPass}. Call only after the native
     * operation succeeded; repeated calls are harmless.
     */
    public void markSubmittedInsideEndPass() {
        requireOpen();
        if (phase == Phase.IN_PASS) {
            throw new IllegalStateException("cannot mark submission inside an active render pass");
        }
        if (phase == Phase.SUBMITTED) {
            return;
        }
        phase = Phase.SUBMITTED;
        submittedInsideEndPass = true;
    }

    /**
     * Closes the state. Idempotent. An active pass is not ended by this call: the wrapper must end
     * or abandon its backend pass first and should read {@link #inPass()} beforehand.
     */
    public void close() {
        if (phase == Phase.CLOSED) {
            return;
        }
        phase = Phase.CLOSED;
        pipelineBound = false;
        vertexBufferBound = false;
    }

    private void requireInPass(String operation) {
        requireOpen();
        if (phase != Phase.IN_PASS) {
            throw new IllegalStateException(operation + " outside render pass");
        }
    }
}
