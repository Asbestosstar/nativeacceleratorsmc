package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/** Pure lifecycle tests; this does not claim a Vulkan runtime or device. */
public final class NativeVulkanDeviceStateTest {
    public static void main(String[] args) {
        NativeVulkanDeviceState state = new NativeVulkanDeviceState();
        require(state.isOpen(), "new state is not open");
        state.requireOpen();
        state.markLost("test device loss");
        require(state.phase() == NativeVulkanDeviceState.Phase.LOST, "loss transition missing");
        require("test device loss".equals(state.lossReason()), "loss reason missing");
        requireThrows(state::requireOpen, "lost device accepted work");
        require(state.beginClose(), "lost device did not enter close");
        require(!state.beginClose(), "close transition was not idempotent");
        state.markLost("late loss during close");
        require(state.phase() == NativeVulkanDeviceState.Phase.CLOSING,
                "late device loss reverted the close transition");
        state.finishClose();
        require(state.phase() == NativeVulkanDeviceState.Phase.CLOSED, "close did not finish");
        requireThrows(state::requireOpen, "closed device accepted work");
        System.out.println("NativeVulkanDeviceStateTest: PASS");
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
