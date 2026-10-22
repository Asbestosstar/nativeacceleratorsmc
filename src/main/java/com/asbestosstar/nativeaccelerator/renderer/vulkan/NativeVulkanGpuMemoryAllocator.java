package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMappedMemoryRange;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Own-device Vulkan memory manager used by the future RenderPearl device.
 *
 * <p>This is deliberately separate from {@link NativeVulkanMemoryAllocator}. The latter is
 * device-independent free-list bookkeeping; this class owns actual {@code VkDeviceMemory} handles
 * and is the only layer allowed to map, flush, invalidate, or free them. The first implementation
 * uses one Vulkan allocation per resource. That is correct and inspectable while resource binding is
 * being brought up; suballocation can be added behind this API without changing RenderPearl
 * resource wrappers.</p>
 *
 * <p>The class is not wired into {@code NativeVulkanGpuBackend} yet. It must not be used as evidence
 * that the complete adapter is ready: buffers/textures, command submission, deferred retirement,
 * and real WSI still have to be completed and validated.</p>
 */
public final class NativeVulkanGpuMemoryAllocator implements AutoCloseable {
    private final NativeVulkanDeviceBootstrap bootstrap;
    private final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties memoryProperties;
    private final long nonCoherentAtomSize;
    private final List<Allocation> allocations = new ArrayList<>();
    private boolean closed;

    public NativeVulkanGpuMemoryAllocator(NativeVulkanDeviceBootstrap bootstrap) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.device = bootstrap.device();
        this.memoryProperties = VkPhysicalDeviceMemoryProperties.calloc();
        VK10.vkGetPhysicalDeviceMemoryProperties(bootstrap.physicalDevice(), memoryProperties);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var properties = org.lwjgl.vulkan.VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(bootstrap.physicalDevice(), properties);
            long atom = properties.limits().nonCoherentAtomSize();
            this.nonCoherentAtomSize = atom > 0L ? atom : 1L;
        }
        if (memoryProperties.memoryTypeCount() <= 0) {
            memoryProperties.free();
            throw new IllegalStateException("Vulkan device reported no memory types");
        }
    }

    /**
     * Allocates memory satisfying the Vulkan resource requirements.
     *
     * @param requirements requirements returned by {@code vkGet*MemoryRequirements}
     * @param requiredFlags flags that must be present
     * @param preferredFlags flags used to rank otherwise valid memory types
     * @param mapImmediately whether host-visible memory should be mapped now
     */
    public synchronized Allocation allocate(VkMemoryRequirements requirements,
                                             int requiredFlags,
                                             int preferredFlags,
                                             boolean mapImmediately) {
        checkOpen();
        Objects.requireNonNull(requirements, "requirements");
        long size = requirements.size();
        long alignment = requirements.alignment();
        if (size <= 0L || alignment <= 0L) {
            throw new IllegalArgumentException("Invalid Vulkan memory requirements");
        }
        int memoryType = chooseMemoryType(requirements.memoryTypeBits(), requiredFlags, preferredFlags);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryAllocateInfo info = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(size)
                    .memoryTypeIndex(memoryType);
            LongBuffer result = stack.mallocLong(1);
            int status = VK10.vkAllocateMemory(device, info, null, result);
            if (status != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkAllocateMemory failed with " + status
                        + " for " + size + " bytes and memory type " + memoryType);
            }
            Allocation allocation = new Allocation(device, result.get(0), size, alignment, memoryType,
                    memoryProperties.memoryTypes(memoryType).propertyFlags(), nonCoherentAtomSize);
            allocations.add(allocation);
            try {
                if (mapImmediately && allocation.hostVisible()) {
                    allocation.map();
                }
                return allocation;
            } catch (Throwable failure) {
                allocations.remove(allocation);
                try {
                    allocation.close();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
    }

    public synchronized int chooseMemoryType(int typeBits, int requiredFlags, int preferredFlags) {
        checkOpen();
        if (typeBits == 0) throw new IllegalArgumentException("No Vulkan memory type bits supplied");
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        int count = memoryProperties.memoryTypeCount();
        for (int index = 0; index < count; index++) {
            if ((typeBits & (1 << index)) == 0) continue;
            int flags = memoryProperties.memoryTypes(index).propertyFlags();
            if ((flags & requiredFlags) != requiredFlags) continue;
            int score = Integer.bitCount(flags & preferredFlags);
            if (best < 0 || score > bestScore) {
                best = index;
                bestScore = score;
            }
        }
        if (best < 0) {
            throw new IllegalArgumentException("No Vulkan memory type satisfies required flags 0x"
                    + Integer.toHexString(requiredFlags) + " for type bits 0x"
                    + Integer.toHexString(typeBits));
        }
        return best;
    }

    public synchronized int memoryTypeCount() {
        checkOpen();
        return memoryProperties.memoryTypeCount();
    }

    public synchronized long liveAllocationCount() {
        return allocations.size();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    boolean belongsTo(NativeVulkanDeviceBootstrap candidate) {
        return bootstrap == candidate;
    }

    public synchronized void free(Allocation allocation) {
        Objects.requireNonNull(allocation, "allocation");
        if (closed) return;
        if (!allocations.remove(allocation)) {
            throw new IllegalArgumentException("Allocation belongs to another allocator or was freed");
        }
        allocation.close();
    }

    /** Flushes a non-coherent mapped range; coherent memory does not require a Vulkan call. */
    public synchronized void flush(Allocation allocation, long offset, long size) {
        validateOwned(allocation);
        allocation.flush(offset, size);
    }

    /** Invalidates a non-coherent mapped range before host reads. */
    public synchronized void invalidate(Allocation allocation, long offset, long size) {
        validateOwned(allocation);
        allocation.invalidate(offset, size);
    }

    private void validateOwned(Allocation allocation) {
        Objects.requireNonNull(allocation, "allocation");
        if (!allocations.contains(allocation)) {
            throw new IllegalArgumentException("Allocation belongs to another allocator or was freed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        Throwable first = null;
        for (int index = allocations.size() - 1; index >= 0; index--) {
            try {
                allocations.get(index).close();
            } catch (Throwable failure) {
                if (first == null) first = failure;
                else first.addSuppressed(failure);
            }
        }
        allocations.clear();
        try {
            memoryProperties.free();
        } catch (Throwable failure) {
            if (first == null) first = failure;
            else first.addSuppressed(failure);
        }
        if (first != null) {
            if (first instanceof RuntimeException runtime) throw runtime;
            if (first instanceof Error error) throw error;
            throw new IllegalStateException("Vulkan memory allocator cleanup failed", first);
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Vulkan memory allocator is closed");
    }

    public static final class Allocation implements AutoCloseable {
        private final VkDevice device;
        private final long memory;
        private final long size;
        private final long alignment;
        private final int memoryType;
        private final int propertyFlags;
        private final long nonCoherentAtomSize;
        private ByteBuffer mapped;
        private boolean closed;

        private Allocation(VkDevice device, long memory, long size, long alignment,
                           int memoryType, int propertyFlags, long nonCoherentAtomSize) {
            this.device = device;
            this.memory = memory;
            this.size = size;
            this.alignment = alignment;
            this.memoryType = memoryType;
            this.propertyFlags = propertyFlags;
            this.nonCoherentAtomSize = nonCoherentAtomSize;
        }

        public long memory() { return memory; }
        public long size() { return size; }
        public long alignment() { return alignment; }
        public int memoryType() { return memoryType; }
        public int propertyFlags() { return propertyFlags; }
        public boolean hostVisible() { return (propertyFlags & VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0; }
        public boolean hostCoherent() { return (propertyFlags & VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0; }
        public synchronized boolean isMapped() { return mapped != null; }

        public synchronized ByteBuffer map() {
            ensureOpen();
            if (!hostVisible()) throw new IllegalStateException("Vulkan memory is not host visible");
            if (mapped != null) return mapped;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pointer = stack.mallocPointer(1);
                int status = VK10.vkMapMemory(device, memory, 0L, VK10.VK_WHOLE_SIZE, 0, pointer);
                if (status != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkMapMemory failed with " + status);
                }
                mapped = org.lwjgl.system.MemoryUtil.memByteBuffer(pointer.get(0), Math.toIntExact(size));
                return mapped;
            }
        }

        public synchronized void unmap() {
            if (mapped == null) return;
            VK10.vkUnmapMemory(device, memory);
            mapped = null;
        }

        private synchronized void flush(long offset, long rangeSize) {
            validateRange(offset, rangeSize);
            if (hostCoherent()) return;
            long alignedOffset = NativeVulkanResourceValidation.alignedOffset(offset, nonCoherentAtomSize);
            long end = offset + rangeSize;
            long alignedEnd = NativeVulkanResourceValidation.alignedEnd(end, nonCoherentAtomSize, size);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMappedMemoryRange range = VkMappedMemoryRange.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE)
                        .memory(memory).offset(alignedOffset).size(alignedEnd - alignedOffset);
                int status = VK10.vkFlushMappedMemoryRanges(device, range);
                if (status != VK10.VK_SUCCESS) throw new IllegalStateException("vkFlushMappedMemoryRanges failed with " + status);
            }
        }

        private synchronized void invalidate(long offset, long rangeSize) {
            validateRange(offset, rangeSize);
            if (hostCoherent()) return;
            long alignedOffset = NativeVulkanResourceValidation.alignedOffset(offset, nonCoherentAtomSize);
            long end = offset + rangeSize;
            long alignedEnd = NativeVulkanResourceValidation.alignedEnd(end, nonCoherentAtomSize, size);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMappedMemoryRange range = VkMappedMemoryRange.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE)
                        .memory(memory).offset(alignedOffset).size(alignedEnd - alignedOffset);
                int status = VK10.vkInvalidateMappedMemoryRanges(device, range);
                if (status != VK10.VK_SUCCESS) throw new IllegalStateException("vkInvalidateMappedMemoryRanges failed with " + status);
            }
        }

        private void validateRange(long offset, long rangeSize) {
            if (offset < 0L || rangeSize <= 0L || offset > size - rangeSize) {
                throw new IllegalArgumentException("Mapped range outside allocation");
            }
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("Vulkan memory allocation is closed");
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (mapped != null) {
                try { VK10.vkUnmapMemory(device, memory); } finally { mapped = null; }
            }
            VK10.vkFreeMemory(device, memory, null);
        }
    }
}
