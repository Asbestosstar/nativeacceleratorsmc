package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/**
 * Dependency-light argument tests for the SDL bridge. The pointer conversion and real SDL calls are
 * covered by the explicit GPU smoke harness; this test deliberately does not initialize LWJGL native
 * libraries so it remains runnable on build hosts without SDL/Vulkan natives.
 */
public final class SdlVulkanWsiBridgeTest {
    public static void main(String[] args) {
        requireThrows(() -> SdlVulkanWsiBridge.extensionsFromPointers(null),
                NullPointerException.class, "null extension buffer accepted");
        requireThrows(() -> SdlVulkanWsiBridge.createSurface(0L, null),
                IllegalArgumentException.class, "zero SDL window accepted");
        requireThrows(() -> SdlVulkanWsiBridge.destroySurface(null, 1L),
                NullPointerException.class, "null Vulkan instance accepted");
        System.out.println("SdlVulkanWsiBridgeTest: PASS");
    }

    private static void requireThrows(Runnable action, Class<? extends Throwable> type, String message) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError(message + " with wrong exception " + failure, failure);
        }
        throw new AssertionError(message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
