package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Device-independent allocation bookkeeping for the Native Vulkan adapter.
 *
 * <p>This class deliberately does not call Vulkan and does not pretend that a returned offset is a
 * {@code VkDeviceMemory} handle. The Vulkan device layer will pair each block with a real memory
 * allocation and use this class only for aligned suballocation and retirement bookkeeping.</p>
 */
public final class NativeVulkanMemoryAllocator {
    public static final long DEFAULT_BLOCK_SIZE = 64L * 1024L * 1024L;
    public static final long DEFAULT_DEDICATED_THRESHOLD = 32L * 1024L * 1024L;

    private final long blockSize;
    private final long dedicatedThreshold;
    private final AtomicLong nextBlockId = new AtomicLong(1L);
    private final Map<Long, Block> blocks = new HashMap<>();
    private final Map<Long, Allocation> allocations = new HashMap<>();
    private final List<Retired> retired = new ArrayList<>();
    private boolean closed;

    public NativeVulkanMemoryAllocator() {
        this(DEFAULT_BLOCK_SIZE, DEFAULT_DEDICATED_THRESHOLD);
    }

    public NativeVulkanMemoryAllocator(long blockSize, long dedicatedThreshold) {
        if (blockSize <= 0L) throw new IllegalArgumentException("blockSize must be positive");
        if (dedicatedThreshold <= 0L) throw new IllegalArgumentException("dedicatedThreshold must be positive");
        this.blockSize = blockSize;
        this.dedicatedThreshold = dedicatedThreshold;
    }

    /** Allocate an aligned range. The returned offset is meaningful only within its allocation block. */
    public synchronized Allocation allocate(int memoryType, long size, long alignment, boolean dedicated) {
        checkOpen();
        if (memoryType < 0) throw new IllegalArgumentException("memoryType must not be negative");
        if (size <= 0L) throw new IllegalArgumentException("size must be positive");
        if (alignment <= 0L || (alignment & (alignment - 1L)) != 0L) {
            throw new IllegalArgumentException("alignment must be a positive power of two");
        }

        boolean useDedicated = dedicated || size >= dedicatedThreshold;
        if (useDedicated) {
            long capacity = checkedAdd(size, alignment - 1L);
            Block block = new Block(nextBlockId.getAndIncrement(), memoryType, capacity, true);
            blocks.put(block.id, block);
            Allocation allocation = block.allocate(size, alignment);
            allocations.put(allocation.id, allocation);
            return allocation;
        }

        for (Block block : blocks.values()) {
            if (!block.dedicated && block.memoryType == memoryType) {
                Allocation allocation = block.tryAllocate(size, alignment);
                if (allocation != null) {
                    allocations.put(allocation.id, allocation);
                    return allocation;
                }
            }
        }

        long capacity = Math.max(blockSize, checkedAdd(size, alignment - 1L));
        Block block = new Block(nextBlockId.getAndIncrement(), memoryType, capacity, false);
        blocks.put(block.id, block);
        Allocation allocation = block.allocate(size, alignment);
        allocations.put(allocation.id, allocation);
        return allocation;
    }

    /** Release an allocation after the caller has proved that no GPU submission can reference it. */
    public synchronized void free(Allocation allocation) {
        Allocation owned = requireOwned(allocation);
        if (owned.released) throw new IllegalStateException("allocation already released: " + owned.id);
        if (owned.retired) throw new IllegalStateException("allocation is pending retirement: " + owned.id);
        owned.released = true;
        owned.block.free(owned);
        allocations.remove(owned.id);
        removeEmptyDedicatedBlock(owned.block);
    }

    /** Defer release until the caller reports that the submission token has completed. */
    public synchronized void retire(Allocation allocation, long completionToken) {
        Allocation owned = requireOwned(allocation);
        if (owned.released) throw new IllegalStateException("allocation already released: " + owned.id);
        if (owned.retired) throw new IllegalStateException("allocation already pending retirement: " + owned.id);
        if (completionToken < 0L) throw new IllegalArgumentException("completionToken must not be negative");
        owned.retired = true;
        retired.add(new Retired(owned, completionToken));
    }

    /** Release all retired allocations whose fence/timeline token is complete. */
    public synchronized int collect(long completedToken) {
        if (completedToken < 0L) throw new IllegalArgumentException("completedToken must not be negative");
        int released = 0;
        for (int i = retired.size() - 1; i >= 0; i--) {
            Retired candidate = retired.get(i);
            if (candidate.completionToken <= completedToken) {
                Allocation allocation = candidate.allocation;
                if (!allocation.released) {
                    allocation.retired = false;
                    allocation.released = true;
                    allocation.block.free(allocation);
                    allocations.remove(allocation.id);
                    removeEmptyDedicatedBlock(allocation.block);
                    released++;
                }
                retired.remove(i);
            }
        }
        return released;
    }

    public synchronized long liveAllocationCount() {
        return allocations.size();
    }

    public synchronized long liveBlockCount() {
        return blocks.size();
    }

    public synchronized void close() {
        if (closed) return;
        if (!retired.isEmpty() || !allocations.isEmpty()) {
            throw new IllegalStateException("cannot close allocator with live allocations");
        }
        blocks.clear();
        closed = true;
    }

    private Allocation requireOwned(Allocation allocation) {
        Objects.requireNonNull(allocation, "allocation");
        Allocation owned = allocations.get(allocation.id);
        if (owned != allocation) throw new IllegalArgumentException("allocation belongs to another allocator");
        return owned;
    }

    private void removeEmptyDedicatedBlock(Block block) {
        if (block.dedicated && block.isEmpty()) blocks.remove(block.id);
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("allocator is closed");
    }

    private static long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("allocation size overflow", overflow);
        }
    }

    public static final class Allocation {
        private final long id;
        private final Block block;
        private final long offset;
        private final long size;
        private boolean released;
        private boolean retired;

        private Allocation(long id, Block block, long offset, long size) {
            this.id = id;
            this.block = block;
            this.offset = offset;
            this.size = size;
        }

        public long id() { return id; }
        public long blockId() { return block.id; }
        public int memoryType() { return block.memoryType; }
        public long offset() { return offset; }
        public long size() { return size; }
        public boolean dedicated() { return block.dedicated; }
        public boolean released() { return released; }
    }

    private record Retired(Allocation allocation, long completionToken) {}

    private static final class Block {
        private static final Comparator<Range> BY_OFFSET = Comparator.comparingLong(range -> range.offset);
        private final long id;
        private final int memoryType;
        private final long capacity;
        private final boolean dedicated;
        private final List<Range> free = new ArrayList<>();
        private final Map<Long, Allocation> live = new HashMap<>();

        private Block(long id, int memoryType, long capacity, boolean dedicated) {
            this.id = id;
            this.memoryType = memoryType;
            this.capacity = capacity;
            this.dedicated = dedicated;
            free.add(new Range(0L, capacity));
        }

        private Allocation tryAllocate(long size, long alignment) {
            for (int i = 0; i < free.size(); i++) {
                Range range = free.get(i);
                long aligned = alignUp(range.offset, alignment);
                long end;
                try {
                    end = Math.addExact(aligned, size);
                } catch (ArithmeticException overflow) {
                    continue;
                }
                if (end > range.end()) continue;
                free.remove(i);
                if (range.offset < aligned) free.add(new Range(range.offset, aligned));
                if (end < range.end()) free.add(new Range(end, range.end()));
                free.sort(BY_OFFSET);
                Allocation allocation = new Allocation(ALLOCATION_IDS.getAndIncrement(), this, aligned, size);
                live.put(allocation.id, allocation);
                return allocation;
            }
            return null;
        }

        private Allocation allocate(long size, long alignment) {
            Allocation allocation = tryAllocate(size, alignment);
            if (allocation == null) throw new IllegalStateException("new block cannot satisfy allocation");
            return allocation;
        }

        private void free(Allocation allocation) {
            Allocation removed = live.remove(allocation.id);
            if (removed != allocation) throw new IllegalStateException("allocation is not live in its block");
            free.add(new Range(allocation.offset, allocation.offset + allocation.size));
            free.sort(BY_OFFSET);
            for (int i = free.size() - 1; i > 0; i--) {
                Range current = free.get(i);
                Range previous = free.get(i - 1);
                if (previous.end() >= current.offset) {
                    free.set(i - 1, new Range(previous.offset, Math.max(previous.end(), current.end())));
                    free.remove(i);
                }
            }
        }

        private boolean isEmpty() { return live.isEmpty(); }
    }

    private record Range(long offset, long end) {
        private Range {
            if (offset < 0L || end <= offset) throw new IllegalArgumentException("invalid free range");
        }
    }

    private static final AtomicLong ALLOCATION_IDS = new AtomicLong(1L);

    private static long alignUp(long value, long alignment) {
        long mask = alignment - 1L;
        if (value > Long.MAX_VALUE - mask) throw new IllegalArgumentException("alignment overflow");
        return (value + mask) & ~mask;
    }
}
