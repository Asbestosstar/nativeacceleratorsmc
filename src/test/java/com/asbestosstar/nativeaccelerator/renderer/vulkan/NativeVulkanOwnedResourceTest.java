package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/** Pure resource ownership/lifetime tests; no Vulkan loader or GPU is required. */
public final class NativeVulkanOwnedResourceTest {
    public static void main(String[] args) {
        NativeVulkanDeviceState first = new NativeVulkanDeviceState();
        NativeVulkanDeviceState second = new NativeVulkanDeviceState();
        ProbeResource resource = new ProbeResource(first);
        ProbeResource sameDevice = new ProbeResource(first);
        ProbeResource foreign = new ProbeResource(second);

        resource.requireForTest();
        resource.requireSameForTest(sameDevice);
        requireThrows(() -> resource.requireSameForTest(foreign), "foreign resource accepted");
        resource.close();
        resource.close();
        require(resource.closeCount == 1, "resource close was not idempotent");
        requireThrows(resource::requireForTest, "closed resource accepted use");
        System.out.println("NativeVulkanOwnedResourceTest: PASS");
    }

    private static final class ProbeResource extends NativeVulkanOwnedResource {
        private int closeCount;

        private ProbeResource(NativeVulkanDeviceState state) {
            super(state);
        }

        private void requireForTest() {
            requireUsable();
        }

        private void requireSameForTest(ProbeResource other) {
            requireSameDevice(other);
        }

        @Override
        protected void closeOwnedHandle() {
            closeCount++;
        }
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalStateException | IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
