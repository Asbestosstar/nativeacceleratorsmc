package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/** Dependency-light allocator bookkeeping tests; these do not claim Vulkan execution. */
public final class NativeVulkanMemoryAllocatorTest {
    public static void main(String[] args) {
        NativeVulkanMemoryAllocator allocator = new NativeVulkanMemoryAllocator(256, 128);
        NativeVulkanMemoryAllocator.Allocation first = allocator.allocate(0, 17, 16, false);
        require(first.offset() == 0, "first allocation was not aligned");
        NativeVulkanMemoryAllocator.Allocation second = allocator.allocate(0, 31, 32, false);
        require(second.offset() % 32 == 0, "second allocation was not aligned");
        allocator.free(first);
        NativeVulkanMemoryAllocator.Allocation reused = allocator.allocate(0, 8, 8, false);
        require(reused.offset() == 0, "freed range was not reused");

        NativeVulkanMemoryAllocator.Allocation dedicated = allocator.allocate(1, 128, 64, false);
        require(dedicated.dedicated(), "threshold allocation was not dedicated");
        allocator.retire(second, 4);
        require(allocator.collect(3) == 0, "retirement released too early");
        require(allocator.collect(4) == 1, "retirement was not collected");

        allocator.free(reused);
        allocator.free(dedicated);
        allocator.close();
        require(allocator.liveAllocationCount() == 0, "allocator retained allocations");
        System.out.println("NativeVulkanMemoryAllocatorTest: PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
