package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/** Pure queue-family selection tests; no Vulkan loader or device is required. */
public final class NativeVulkanQueueSelectionTest {
    public static void main(String[] args) {
        NativeVulkanQueueSelection.Selection selected = NativeVulkanQueueSelection.select(
                new int[]{NativeVulkanQueueSelection.GRAPHICS_BIT,
                        NativeVulkanQueueSelection.GRAPHICS_BIT | NativeVulkanQueueSelection.COMPUTE_BIT},
                new int[]{1, 2}, new boolean[]{true, true});
        require(selected.graphicsFamily() == 1, "combined queue family was not preferred");
        require(selected.combined(), "graphics and present queues were split");
        requireThrows(() -> NativeVulkanQueueSelection.select(
                new int[]{NativeVulkanQueueSelection.GRAPHICS_BIT}, new int[]{1}, new boolean[]{true}),
                "graphics-only queue was accepted");
        requireThrows(() -> NativeVulkanQueueSelection.select(
                new int[]{NativeVulkanQueueSelection.GRAPHICS_BIT | NativeVulkanQueueSelection.COMPUTE_BIT},
                new int[]{1}, new boolean[]{false}), "non-present queue was accepted");
        NativeVulkanQueueSelection.Selection split = NativeVulkanQueueSelection.select(
                new int[]{NativeVulkanQueueSelection.GRAPHICS_BIT | NativeVulkanQueueSelection.COMPUTE_BIT,
                        0}, new int[]{2, 1}, new boolean[]{false, true});
        require(split.graphicsFamily() == 0 && split.presentFamily() == 1, "split queues were not selected");
        require(!split.combined(), "split queues were marked combined");
        System.out.println("NativeVulkanQueueSelectionTest: PASS");
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
