package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/** Dependency-light ownership lifecycle checks. */
public final class NativeVulkanOwnershipTest {
    public static void main(String[] args) {
        NativeVulkanOwnership.releaseFailedAttempt();
        require(NativeVulkanOwnership.tryClaim(), "first claim failed");
        require(!NativeVulkanOwnership.tryClaim(), "duplicate claim accepted");
        NativeVulkanOwnership.releaseFailedAttempt();
        require(NativeVulkanOwnership.tryClaim(), "released failed claim was not reusable");
        NativeVulkanOwnership.commit();
        require(NativeVulkanOwnership.committed(), "commit was not recorded");
        boolean rejected = false;
        try {
            NativeVulkanOwnership.releaseFailedAttempt();
        } catch (IllegalStateException expected) {
            rejected = true;
        } finally {
            // Keep the standalone test repeatable without weakening production's committed-state rule.
            System.getProperties().remove("nativeaccelerator.renderer.nativeBackend.device.committed");
            System.getProperties().remove("nativeaccelerator.renderer.nativeBackend.device");
        }
        require(rejected, "committed claim was released");
        System.out.println("NativeVulkanOwnershipTest: PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
