package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/** Pure validation tests for queue-family evidence captured before surface creation. */
public final class NativeVulkanQueueFamiliesTest {
    public static void main(String[] args) {
        NativeVulkanQueueFamilies evidence = new NativeVulkanQueueFamilies(
                new int[]{NativeVulkanQueueSelection.GRAPHICS_BIT, NativeVulkanQueueSelection.COMPUTE_BIT},
                new int[]{1, 2});
        require(evidence.size() == 2, "queue-family count mismatch");
        int[] flags = evidence.queueFlags();
        flags[0] = 0;
        require(evidence.queueFlags()[0] != 0, "queue flags were not defensively copied");
        requireThrows(() -> new NativeVulkanQueueFamilies(new int[]{1}, new int[]{}));
        requireThrows(() -> new NativeVulkanQueueFamilies(new int[]{1}, new int[]{-1}));
        System.out.println("NativeVulkanQueueFamiliesTest: PASS");
    }

    private static void requireThrows(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
