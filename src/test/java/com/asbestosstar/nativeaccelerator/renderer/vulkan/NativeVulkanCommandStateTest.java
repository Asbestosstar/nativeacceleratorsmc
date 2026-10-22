package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/**
 * Pure command-recording lifecycle tests. This proves only that illegal recording sequences are
 * rejected; it does not create a Vulkan device, command buffer, or any GPU resource.
 */
public final class NativeVulkanCommandStateTest {
    private static final String TARGET = "test-target";
    private static final Object PIPELINE = "test-pipeline";
    private static final Object VERTEX_BUFFER = "test-vertex-buffer";

    public static void main(String[] args) {
        newStateIsRecordable();
        passEntryIsValidated();
        nestedPassIsRejected();
        pipelineBindingIsValidated();
        vertexBindingIsValidated();
        drawRequiresCompleteState();
        drawArgumentsAreValidated();
        indexedDrawRequiresCompleteState();
        indexRangeIsValidated();
        leavingAPassResetsBindings();
        submitConventionIsValidated();
        endPassSubmissionConvention();
        closeIsTerminalAndIdempotent();
        System.out.println("NativeVulkanCommandStateTest: PASS");
    }

    private static void newStateIsRecordable() {
        NativeVulkanCommandState state = new NativeVulkanCommandState();
        require(state.phase() == NativeVulkanCommandState.Phase.RECORDING, "new state is not recording");
        require(!state.isClosed(), "new state is closed");
        require(!state.inPass(), "new state is in a pass");
        require(!state.isSubmitted(), "new state is submitted");
        require(!state.pipelineBound(), "new state has a pipeline");
        require(!state.vertexBufferBound(), "new state has a vertex buffer");
        state.requireOpen();
        state.requireCanBeginPass("pass", TARGET);
    }

    private static void passEntryIsValidated() {
        NativeVulkanCommandState state = new NativeVulkanCommandState();
        requireThrows(IllegalArgumentException.class,
                () -> state.requireCanBeginPass(null, TARGET), "null pass name accepted");
        requireThrows(IllegalArgumentException.class,
                () -> state.requireCanBeginPass("   ", TARGET), "blank pass name accepted");
        requireThrows(IllegalStateException.class,
                () -> state.requireCanBeginPass("pass", null), "null color target accepted");
        require(state.phase() == NativeVulkanCommandState.Phase.RECORDING,
                "rejected beginPass changed state");
        requireThrows(IllegalStateException.class,
                state::requireCanEndPass, "endPass accepted without a pass");
    }

    private static void nestedPassIsRejected() {
        NativeVulkanCommandState state = new NativeVulkanCommandState();
        state.requireCanBeginPass("pass", TARGET);
        state.enterPass();
        require(state.inPass(), "pass was not entered");
        require(state.phase() == NativeVulkanCommandState.Phase.IN_PASS, "phase is not IN_PASS");
        requireThrows(IllegalStateException.class,
                () -> state.requireCanBeginPass("inner", TARGET), "nested pass accepted");
        requireThrows(IllegalStateException.class,
                state::enterPass, "enterPass accepted a nested pass");
        require(state.inPass(), "rejected nested pass left the pass");
    }

    private static void pipelineBindingIsValidated() {
        NativeVulkanCommandState state = new NativeVulkanCommandState();
        requireThrows(IllegalStateException.class,
                () -> state.requireCanSetPipeline(PIPELINE), "pipeline accepted outside a pass");
        state.enterPass();
        requireThrows(IllegalStateException.class,
                () -> state.requireCanSetPipeline(null), "null pipeline accepted");
        require(!state.pipelineBound(), "rejected pipeline was marked bound");
        state.requireCanSetPipeline(PIPELINE);
        state.markPipelineBound();
        require(state.pipelineBound(), "pipeline was not marked bound");
    }

    private static void vertexBindingIsValidated() {
        NativeVulkanCommandState state = new NativeVulkanCommandState();
        requireThrows(IllegalStateException.class,
                () -> state.requireCanSetVertexBuffer(0, VERTEX_BUFFER, 0, 64, 16),
                "vertex buffer accepted outside a pass");
        state.enterPass();
        requireThrows(IllegalStateException.class,
                () -> state.requireCanSetVertexBuffer(-1, VERTEX_BUFFER, 0, 64, 16),
                "negative slot accepted");
        requireThrows(IllegalStateException.class,
                () -> state.requireCanSetVertexBuffer(16, VERTEX_BUFFER, 0, 64, 16),
                "slot at the exclusive bound accepted");
        requireThrows(IllegalStateException.class,
                () -> state.requireCanSetVertexBuffer(1, VERTEX_BUFFER, 0, 64, 1),
                "slot outside a one-slot backend accepted");
        requireThrows(IllegalStateException.class,
                () -> state.requireCanSetVertexBuffer(0, null, 0, 64, 16),
                "null vertex buffer accepted");
        requireThrows(IllegalArgumentException.class,
                () -> state.requireCanSetVertexBuffer(0, VERTEX_BUFFER, -1, 64, 16),
                "negative offset accepted");
        requireThrows(IllegalStateException.class,
                () -> state.requireCanSetVertexBuffer(0, VERTEX_BUFFER, 64, 64, 16),
                "offset at the buffer size accepted");
        require(!state.vertexBufferBound(), "rejected vertex buffer was marked bound");
        state.requireCanSetVertexBuffer(0, VERTEX_BUFFER, 0, 64, 16);
        state.markVertexBufferBound();
        require(state.vertexBufferBound(), "vertex buffer was not marked bound");
    }

    private static void drawRequiresCompleteState() {
        NativeVulkanCommandState state = new NativeVulkanCommandState();
        requireThrows(IllegalStateException.class,
                () -> state.draw(3, 0), "draw accepted outside a pass");
        state.enterPass();
        requireThrows(IllegalStateException.class,
                () -> state.draw(3, 0), "draw accepted without a pipeline or vertex buffer");
        state.markPipelineBound();
        requireThrows(IllegalStateException.class,
                () -> state.draw(3, 0), "draw accepted without a vertex buffer");
        state.markVertexBufferBound();
        state.draw(3, 0);
        require(state.inPass(), "draw left the pass");
    }

    private static void drawArgumentsAreValidated() {
        NativeVulkanCommandState state = new NativeVulkanCommandState();
        state.enterPass();
        state.markPipelineBound();
        state.markVertexBufferBound();
        requireThrows(IllegalArgumentException.class,
                () -> state.draw(0, 0), "zero vertex count accepted");
        requireThrows(IllegalArgumentException.class,
                () -> state.draw(-1, 0), "negative vertex count accepted");
        requireThrows(IllegalArgumentException.class,
                () -> state.draw(3, -1), "negative first vertex accepted");
        state.draw(1, 0);
        state.draw(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private static void indexedDrawRequiresCompleteState() {
        NativeVulkanCommandState state = new NativeVulkanCommandState();
        requireThrows(IllegalStateException.class, () -> state.drawIndexed(3, 0),
                "indexed draw accepted outside pass");
        state.enterPass();
        requireThrows(IllegalStateException.class, () -> state.drawIndexed(3, 0),
                "indexed draw accepted without bindings");
        state.markPipelineBound();
        requireThrows(IllegalStateException.class, () -> state.drawIndexed(3, 0),
                "indexed draw accepted without vertex buffer");
        state.markVertexBufferBound();
        state.drawIndexed(3, 0);
        requireThrows(IllegalArgumentException.class, () -> state.drawIndexed(0, 0),
                "indexed draw accepted empty count");
        requireThrows(IllegalArgumentException.class, () -> state.drawIndexed(3, -1),
                "indexed draw accepted negative first index");
    }

    private static void indexRangeIsValidated() {
        NativeVulkanCommandState.validateIndexRange(12, 0, 6, Short.BYTES);
        NativeVulkanCommandState.validateIndexRange(24, 1, 5, Integer.BYTES);
        requireThrows(IllegalStateException.class,
                () -> NativeVulkanCommandState.validateIndexRange(10, 0, 6, Short.BYTES),
                "index range beyond buffer accepted");
        requireThrows(IllegalArgumentException.class,
                () -> NativeVulkanCommandState.validateIndexRange(12, -1, 1, Short.BYTES),
                "negative first index accepted");
        requireThrows(IllegalStateException.class,
                () -> NativeVulkanCommandState.validateIndexRange(0, Integer.MAX_VALUE,
                        Integer.MAX_VALUE, Integer.BYTES), "overflowing index range accepted");
    }

    private static void leavingAPassResetsBindings() {
        NativeVulkanCommandState state = new NativeVulkanCommandState();
        state.enterPass();
        state.markPipelineBound();
        state.markVertexBufferBound();
        state.requireCanEndPass();
        state.leavePass();
        require(state.phase() == NativeVulkanCommandState.Phase.RECORDING, "pass did not end");
        require(!state.inPass(), "state still reports a pass");
        require(!state.pipelineBound(), "pipeline survived the pass");
        require(!state.vertexBufferBound(), "vertex buffer survived the pass");
        requireThrows(IllegalStateException.class,
                state::leavePass, "endPass accepted without a pass");
        requireThrows(IllegalStateException.class,
                () -> state.markPipelineBound(), "pipeline marked outside a pass");
        requireThrows(IllegalStateException.class,
                () -> state.markVertexBufferBound(), "vertex buffer marked outside a pass");

        state.enterPass();
        require(!state.pipelineBound() && !state.vertexBufferBound(),
                "second pass inherited bindings");
        state.leavePass();
    }

    private static void submitConventionIsValidated() {
        NativeVulkanCommandState state = new NativeVulkanCommandState();
        state.enterPass();
        requireThrows(IllegalStateException.class, state::submit, "submit accepted inside a pass");
        state.leavePass();
        state.submit();
        require(state.isSubmitted(), "submit did not mark the list submitted");
        requireThrows(IllegalStateException.class, state::submit, "double submit accepted");
        requireThrows(IllegalStateException.class,
                () -> state.requireCanBeginPass("after-submit", TARGET),
                "pass accepted after submission");
        requireThrows(IllegalStateException.class,
                () -> state.requireCanSetPipeline(PIPELINE), "pipeline accepted after submission");
        requireThrows(IllegalStateException.class, () -> state.draw(3, 0), "draw accepted after submission");
        requireThrows(IllegalStateException.class, state::requireCanEndPass, "endPass accepted after submission");
    }

    private static void endPassSubmissionConvention() {
        NativeVulkanCommandState state = new NativeVulkanCommandState();
        state.enterPass();
        requireThrows(IllegalStateException.class,
                state::markSubmittedInsideEndPass, "submission marked inside an active pass");
        state.leavePass();
        state.markSubmittedInsideEndPass();
        require(state.isSubmitted(), "endPass submission was not recorded");
        state.submit();
        state.submit();
        require(state.isSubmitted(), "no-op submit changed submission state");
        state.markSubmittedInsideEndPass();
        require(state.isSubmitted(), "repeated endPass submission changed state");
        requireThrows(IllegalStateException.class,
                () -> state.requireCanBeginPass("after-end-pass-submit", TARGET),
                "pass accepted after endPass submission");
    }

    private static void closeIsTerminalAndIdempotent() {
        NativeVulkanCommandState state = new NativeVulkanCommandState();
        state.close();
        require(state.isClosed(), "close did not close");
        state.close();
        require(state.phase() == NativeVulkanCommandState.Phase.CLOSED, "second close changed state");
        requireThrows(IllegalStateException.class, state::requireOpen, "closed list accepted work");
        requireThrows(IllegalStateException.class,
                () -> state.requireCanBeginPass("closed", TARGET), "closed list began a pass");
        requireThrows(IllegalStateException.class,
                () -> state.requireCanSetPipeline(PIPELINE), "closed list set a pipeline");
        requireThrows(IllegalStateException.class,
                () -> state.requireCanSetVertexBuffer(0, VERTEX_BUFFER, 0, 64, 16),
                "closed list set a vertex buffer");
        requireThrows(IllegalStateException.class, () -> state.draw(3, 0), "closed list drew");
        requireThrows(IllegalStateException.class, state::requireCanEndPass, "closed list ended a pass");
        requireThrows(IllegalStateException.class, state::submit, "closed list submitted");
        requireThrows(IllegalStateException.class,
                state::markSubmittedInsideEndPass, "closed list recorded a submission");

        NativeVulkanCommandState inPass = new NativeVulkanCommandState();
        inPass.enterPass();
        inPass.markPipelineBound();
        inPass.markVertexBufferBound();
        inPass.close();
        require(inPass.isClosed(), "close during a pass did not close");
        require(!inPass.pipelineBound() && !inPass.vertexBufferBound(),
                "close kept bindings alive");
        requireThrows(IllegalStateException.class, inPass::requireOpen,
                "closed in-pass list accepted work");
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }

    private static void requireThrows(Class<? extends Throwable> type, ThrowingAction action, String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return;
            }
            throw new AssertionError(message + " (threw " + thrown.getClass().getSimpleName()
                    + ": " + thrown.getMessage() + ")");
        }
        throw new AssertionError(message + " (nothing was thrown)");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
