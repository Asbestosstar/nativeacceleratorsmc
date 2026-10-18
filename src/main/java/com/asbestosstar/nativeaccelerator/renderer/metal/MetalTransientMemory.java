package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Submission-scoped transient allocator.
 *
 * <p>Small allocations share aligned blocks for the lifetime of this encoder. Blocks are still
 * owned and retired by the command encoder exactly as before; this class deliberately does not
 * recycle a block across submissions until the fence/ring has released the whole transient object.
 * That keeps the allocation optimization independent from GPU lifetime policy.</p>
 */
final class MetalTransientMemory implements TransientMemory {
    private static final int DEFAULT_BLOCK_BYTES = 512 * 1024;

    private final MetalDevice device;
    private final List<MetalGpuBuffer> owned = new ArrayList<>();
    private final List<ByteBuffer> retainedUploadBlocks = new ArrayList<>();
    private final Map<Integer, GpuArena> gpuArenas = new HashMap<>();
    private final CpuArena cpuArena = new CpuArena();

    MetalTransientMemory(MetalDevice device) {
        this.device = device;
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        Allocation allocation = cpuArena.allocate(size, alignment, minimumAllocation, elementSize);
        return allocation.bytes;
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage,
                                                       long minimumAllocation, long elementSize) {
        return allocateMapped(size, alignment, usage | GpuBuffer.USAGE_MAP_WRITE,
                minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage,
                                      long minimumAllocation, long elementSize) {
        Allocation allocation = arena(usage).allocate(size, alignment, minimumAllocation, elementSize);
        return allocation.buffer.slice(allocation.offset, size);
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage,
                                                        long minimumAllocation, long elementSize) {
        return allocateMapped(size, alignment, usage | GpuBuffer.USAGE_MAP_WRITE,
                minimumAllocation, elementSize);
    }

    private GpuBufferSlice.MappedView allocateMapped(long size, long alignment, int usage,
                                                      long minimumAllocation, long elementSize) {
        Allocation allocation = arena(usage).allocate(size, alignment, minimumAllocation, elementSize);
        return allocation.buffer.map(allocation.offset, size, false, true);
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage,
                                        long minimumAllocation, long elementSize) {
        return upload(data, alignment, usage | GpuBuffer.USAGE_MAP_WRITE, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage,
                                    long minimumAllocation, long elementSize) {
        return upload(data, alignment, usage | GpuBuffer.USAGE_MAP_WRITE, minimumAllocation, elementSize);
    }

    private GpuBufferSlice upload(List<ByteBuffer> data, long alignment, int usage,
                                  long minimumAllocation, long elementSize) {
        long effectiveAlignment = checkedAlignment(alignment);
        long total = 0L;
        for (ByteBuffer source : data) {
            total = Math.addExact(alignUp(total, effectiveAlignment), source.remaining());
        }
        Allocation allocation = arena(usage).allocate(total, effectiveAlignment, minimumAllocation, elementSize);
        long cursor = allocation.offset;
        for (ByteBuffer source : data) {
            cursor = allocation.offset + alignUp(cursor - allocation.offset, effectiveAlignment);
            ByteBuffer copy = source.duplicate();
            int length = copy.remaining();
            if (length != 0) {
                allocation.buffer.overwriteShadow(cursor, copy);
                allocation.buffer.markDirty(cursor, length);
            }
            cursor = Math.addExact(cursor, length);
        }
        return allocation.buffer.slice(allocation.offset, total);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
        return multi(data, alignment, usage | GpuBuffer.USAGE_MAP_WRITE);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
        return multi(data, alignment, usage | GpuBuffer.USAGE_MAP_WRITE);
    }

    private List<GpuBufferSlice> multi(List<ByteBuffer> data, long alignment, int usage) {
        if (data.isEmpty()) return List.of();
        long effectiveAlignment = checkedAlignment(alignment);
        long total = 0L;
        long[] offsets = new long[data.size()];
        for (int i = 0; i < data.size(); i++) {
            ByteBuffer source = data.get(i);
            if (source == null) throw new NullPointerException("transient upload buffer");
            total = alignUp(total, effectiveAlignment);
            offsets[i] = total;
            total = Math.addExact(total, source.remaining());
        }
        Allocation allocation = arena(usage).allocate(total, effectiveAlignment, total, 1);
        List<GpuBufferSlice> result = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            ByteBuffer source = data.get(i).duplicate();
            int length = source.remaining();
            long offset = allocation.offset + offsets[i];
            if (length != 0) {
                allocation.buffer.overwriteShadow(offset, source);
                allocation.buffer.markDirty(offset, length);
            }
            result.add(allocation.buffer.slice(offset, length));
        }
        return List.copyOf(result);
    }

    private GpuArena arena(int usage) {
        return gpuArenas.computeIfAbsent(usage, ignored -> new GpuArena(usage));
    }

    private static long totalRemaining(List<ByteBuffer> data) {
        long total = 0L;
        for (ByteBuffer buffer : data) {
            if (buffer == null) throw new NullPointerException("transient upload buffer");
            total = Math.addExact(total, buffer.remaining());
        }
        return total;
    }

    boolean isEmpty() {
        return owned.isEmpty() && retainedUploadBlocks.isEmpty();
    }

    void retainUploadBlocks(List<ByteBuffer> blocks) {
        retainedUploadBlocks.addAll(blocks);
    }

    void release() {
        for (ByteBuffer block : retainedUploadBlocks) {
            MetalUploadBufferPool.release(block);
        }
        retainedUploadBlocks.clear();
        for (GpuBuffer buffer : owned) {
            try {
                buffer.close();
            } catch (Throwable ignored) {
            }
        }
        owned.clear();
        gpuArenas.clear();
        cpuArena.clear();
    }

    private final class GpuArena {
        private final int usage;
        private final List<Block> blocks = new ArrayList<>();

        private GpuArena(int usage) {
            this.usage = usage;
        }

        private Allocation allocate(long size, long alignment, long minimumAllocation, long elementSize) {
            validateSize(size);
            long effectiveAlignment = checkedAlignment(alignment);
            long minimum = Math.max(size, minimumAllocation);
            if (minimum < 0L) throw new IllegalArgumentException("Negative transient minimum allocation: " + minimumAllocation);
            long blockMinimum = roundUp(minimum, Math.max(1L, elementSize));
            for (Block block : blocks) {
                long offset = alignUp(block.cursor, effectiveAlignment);
                if (offset <= block.capacity && size <= block.capacity - offset) {
                    block.cursor = offset + size;
                    return new Allocation(block.buffer, offset, size);
                }
            }

            long capacity = Math.max(DEFAULT_BLOCK_BYTES, blockMinimum);
            if (size > capacity) capacity = size;
            int bytes = checkedSize(capacity);
            MetalGpuBuffer buffer = new MetalGpuBuffer(device, usage, bytes);
            owned.add(buffer);
            Block block = new Block(buffer, bytes);
            blocks.add(block);
            long offset = alignUp(0L, effectiveAlignment);
            if (offset > block.capacity || size > block.capacity - offset) {
                throw new IllegalArgumentException("Transient allocation does not fit block: " + size);
            }
            block.cursor = offset + size;
            return new Allocation(buffer, offset, size);
        }
    }

    private final class CpuArena {
        private final List<CpuBlock> blocks = new ArrayList<>();

        private Allocation allocate(long size, long alignment, long minimumAllocation, long elementSize) {
            validateSize(size);
            long effectiveAlignment = checkedAlignment(alignment);
            long minimum = Math.max(size, minimumAllocation);
            if (minimum < 0L) throw new IllegalArgumentException("Negative transient minimum allocation: " + minimumAllocation);
            long blockMinimum = roundUp(minimum, Math.max(1L, elementSize));
            for (CpuBlock block : blocks) {
                long offset = alignUp(block.cursor, effectiveAlignment);
                if (offset <= block.capacity && size <= block.capacity - offset) {
                    block.cursor = offset + size;
                    return new Allocation(block.bytes, offset, size);
                }
            }
            long capacity = Math.max(DEFAULT_BLOCK_BYTES, blockMinimum);
            if (size > capacity) capacity = size;
            int bytes = checkedSize(capacity);
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            CpuBlock block = new CpuBlock(buffer, bytes);
            blocks.add(block);
            long offset = alignUp(0L, effectiveAlignment);
            if (offset > block.capacity || size > block.capacity - offset) {
                throw new IllegalArgumentException("CPU transient allocation does not fit block: " + size);
            }
            block.cursor = offset + size;
            return new Allocation(buffer, offset, size);
        }

        private void clear() {
            blocks.clear();
        }
    }

    private record Allocation(Object owner, long offset, long size, ByteBuffer bytes, MetalGpuBuffer buffer) {
        private Allocation(MetalGpuBuffer buffer, long offset, long size) {
            this(buffer, offset, size, null, buffer);
        }

        private Allocation(ByteBuffer bytes, long offset, long size) {
            this(bytes, offset, size, view(bytes, offset, size), null);
        }

        private static ByteBuffer view(ByteBuffer source, long offset, long size) {
            ByteBuffer view = source.duplicate().order(ByteOrder.nativeOrder());
            view.position(checkedSize(offset)).limit(checkedSize(offset + size));
            return view.slice().order(ByteOrder.nativeOrder());
        }
    }

    private static final class Block {
        private final MetalGpuBuffer buffer;
        private final long capacity;
        private long cursor;

        private Block(MetalGpuBuffer buffer, long capacity) {
            this.buffer = buffer;
            this.capacity = capacity;
        }
    }

    private static final class CpuBlock {
        private final ByteBuffer bytes;
        private final long capacity;
        private long cursor;

        private CpuBlock(ByteBuffer bytes, long capacity) {
            this.bytes = bytes;
            this.capacity = capacity;
        }
    }

    private static void validateSize(long size) {
        if (size < 0L) throw new IllegalArgumentException("Negative transient allocation: " + size);
    }

    private static long checkedAlignment(long alignment) {
        if (alignment <= 0L) return 1L;
        return alignment;
    }

    private static long alignUp(long value, long alignment) {
        long remainder = value % alignment;
        return remainder == 0L ? value : Math.addExact(value, alignment - remainder);
    }

    private static long roundUp(long value, long quantum) {
        return alignUp(value, quantum);
    }

    private static int checkedSize(long size) {
        if (size < 0L || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Transient allocation too large: " + size);
        }
        return (int) size;
    }
}
