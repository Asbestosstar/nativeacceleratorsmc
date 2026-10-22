package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkDevice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Public-LWJGL Vulkan buffer wrapper used by the future own-device backend.
 *
 * <p>This class owns a real {@code VkBuffer} and a memory allocation. It is deliberately not exposed
 * by the candidate while command submission and deferred retirement are incomplete. Initial data is
 * copied through mapped host-visible memory for now; a later command layer can replace that path with
 * a staging transfer without changing the RenderPearl-facing wrapper.</p>
 *
 * <p>Mapping and close policy: both run under the {@link NativeVulkanDeviceState} monitor. A mapped view
 * is a lease that keeps the Vulkan handle and allocation alive. Closing a buffer with live leases stops
 * new mappings immediately, but the destroy is deferred until the last lease is released; that release
 * flushes host writes while the memory still exists. See {@link Lifecycle}.</p>
 */
public final class NativeVulkanGpuBuffer extends BaseGpuBuffer {
    private final NativeVulkanDeviceState state;
    private final VkDevice device;
    private final NativeVulkanGpuMemoryAllocator allocator;
    private final NativeVulkanGpuMemoryAllocator.Allocation allocation;
    private final long handle;
    private final boolean hostVisible;
    private final Lifecycle lifecycle;

    public NativeVulkanGpuBuffer(NativeVulkanDeviceBootstrap bootstrap,
                                 NativeVulkanDeviceState state,
                                 NativeVulkanGpuMemoryAllocator allocator,
                                 int usage,
                                 long size) {
        super(usage, checkedSize(size));
        NativeVulkanDeviceBootstrap owner = Objects.requireNonNull(bootstrap, "bootstrap");
        this.state = Objects.requireNonNull(state, "state");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        // Ownership and liveness are checked before any VkBuffer or VkDeviceMemory is created.
        if (!allocator.belongsTo(owner)) {
            throw new IllegalArgumentException("Vulkan memory allocator belongs to a different device bootstrap");
        }
        if (allocator.isClosed()) throw new IllegalStateException("Vulkan memory allocator is closed");
        state.requireOpen();
        NativeVulkanResourceValidation.bufferUsage(usage);
        this.device = owner.device();
        long created;
        NativeVulkanGpuMemoryAllocator.Allocation memory;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size())
                    .usage(vulkanUsage(usage))
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            var result = stack.mallocLong(1);
            int status = VK10.vkCreateBuffer(device, info, null, result);
            if (status != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateBuffer failed with " + status);
            }
            created = result.get(0);
            try {
                VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
                VK10.vkGetBufferMemoryRequirements(device, created, requirements);
                boolean needsHost = (usage & (GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE)) != 0;
                int required = needsHost ? VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT : 0;
                int preferred = needsHost
                        ? VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                        : VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
                memory = allocator.allocate(requirements, required, preferred, needsHost);
                status = VK10.vkBindBufferMemory(device, created, memory.memory(), 0L);
                if (status != VK10.VK_SUCCESS) {
                    allocator.free(memory);
                    throw new IllegalStateException("vkBindBufferMemory failed with " + status);
                }
            } catch (Throwable failure) {
                VK10.vkDestroyBuffer(device, created, null);
                throw failure;
            }
        }
        this.handle = created;
        this.allocation = memory;
        this.hostVisible = memory.hostVisible();
        this.lifecycle = new Lifecycle(state, this::destroyResources);
    }

    public NativeVulkanGpuBuffer(NativeVulkanDeviceBootstrap bootstrap,
                                 NativeVulkanDeviceState state,
                                 NativeVulkanGpuMemoryAllocator allocator,
                                 int usage,
                                 ByteBuffer initialData) {
        this(bootstrap, state, allocator, checkedInitialUsage(usage), initialData.remaining());
        try {
            ByteBuffer destination = allocation.map();
            ByteBuffer source = initialData.duplicate();
            destination.put(source);
            allocator.flush(allocation, 0L, size());
        } catch (Throwable failure) {
            // The partially initialized instance never escapes the constructor, so no mapping lease can
            // exist. Closing destroys the VkBuffer and frees its allocation before rethrowing.
            try {
                lifecycle.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static int checkedInitialUsage(int usage) {
        if ((usage & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new UnsupportedOperationException(
                    "Initial Vulkan buffer data requires negotiated staging or map-write support");
        }
        return usage;
    }

    private static long checkedSize(long size) {
        if (size <= 0L) throw new IllegalArgumentException("Vulkan buffer size must be greater than zero");
        return size;
    }

    private static int vulkanUsage(int usage) {
        int flags = 0;
        if ((usage & GpuBuffer.USAGE_COPY_DST) != 0) flags |= VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if ((usage & GpuBuffer.USAGE_COPY_SRC) != 0) flags |= VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        if ((usage & GpuBuffer.USAGE_VERTEX) != 0) flags |= VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        if ((usage & GpuBuffer.USAGE_INDEX) != 0) flags |= VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) flags |= VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        if ((usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) flags |= VK10.VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
        if ((usage & GpuBuffer.USAGE_INDIRECT_PARAMETERS) != 0) flags |= VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        if (flags == 0) throw new IllegalArgumentException("Vulkan buffer has no supported usage bits: 0x" + Integer.toHexString(usage));
        return flags;
    }

    public long handle() {
        requireOpen();
        return handle;
    }

    public NativeVulkanGpuMemoryAllocator.Allocation allocation() {
        requireOpen();
        return allocation;
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        // Validation, the read invalidate, and lease acquisition all happen under the same monitor that
        // close() uses, so a concurrent close cannot free the memory between the checks and the mapping.
        synchronized (state) {
            requireOpen();
            NativeVulkanResourceValidation.map(size(), offset, length, read, write, usage(), hostVisible);
            if (size() > Integer.MAX_VALUE) {
                throw new UnsupportedOperationException("Mapped Vulkan buffers larger than 2 GiB are not supported");
            }
            ByteBuffer whole = allocation.map().order(ByteOrder.nativeOrder());
            if (read) {
                allocator.invalidate(allocation, offset, length);
            }
            ByteBuffer view = whole.duplicate().position(Math.toIntExact(offset))
                    .limit(Math.toIntExact(offset + length)).slice().order(ByteOrder.nativeOrder());
            GpuBufferSlice slice = new GpuBufferSlice(this, offset, length);
            Lifecycle.Lease lease = lifecycle.acquire();
            Runnable flush = write ? () -> flushIfAllocated(offset, length) : null;
            try {
                return new GpuBufferSlice.MappedView(slice, view, () -> lifecycle.release(lease, flush));
            } catch (RuntimeException | Error failure) {
                lifecycle.release(lease, null);
                throw failure;
            }
        }
    }

    @Override
    public boolean isClosed() {
        return lifecycle.isClosed();
    }

    @Override
    public void close() {
        lifecycle.close();
    }

    /** Runs under the device-state monitor, and only once no mapping lease remains. */
    private void destroyResources() {
        NativeVulkanDeviceState.Phase phase = state.phase();
        // During device or allocator teardown, the owner of the device memory releases these handles.
        if (phase == NativeVulkanDeviceState.Phase.CLOSING
                || phase == NativeVulkanDeviceState.Phase.CLOSED || allocator.isClosed()) return;
        VK10.vkDestroyBuffer(device, handle, null);
        allocator.free(allocation);
    }

    private void flushIfAllocated(long offset, long length) {
        if (!allocator.isClosed()) allocator.flush(allocation, offset, length);
    }

    private void requireOpen() {
        state.requireOpen();
        if (lifecycle.isClosed()) throw new IllegalStateException("Vulkan buffer is closed");
    }

    /**
     * Close-versus-mapping policy, kept free of Vulkan calls so it can be tested without a device.
     *
     * <p>Every transition runs while holding {@code monitor}; the buffer passes the device-state object,
     * the same monitor that {@link #close()} has always used. A lease is taken only while the buffer is
     * open. {@link #close()} stops new leases immediately. If leases are still live, the destroyer is
     * deferred until the last {@link #release} runs. That release first runs its flush action, if any,
     * while the memory still exists, and then destroys the resources. A close therefore never frees
     * memory under a mapped view, and it never throws because a mapping is outstanding.</p>
     */
    static final class Lifecycle {
        private final Object monitor;
        private final Runnable destroyer;
        private volatile boolean closed;
        private boolean destroyed;
        private int activeLeases;

        Lifecycle(Object monitor, Runnable destroyer) {
            this.monitor = Objects.requireNonNull(monitor, "monitor");
            this.destroyer = Objects.requireNonNull(destroyer, "destroyer");
        }

        boolean isClosed() {
            return closed;
        }

        boolean isDestroyed() {
            synchronized (monitor) {
                return destroyed;
            }
        }

        int activeLeases() {
            synchronized (monitor) {
                return activeLeases;
            }
        }

        /** Returns true only for the call that performs the open-to-closed transition. */
        boolean close() {
            synchronized (monitor) {
                if (closed) return false;
                closed = true;
                if (activeLeases == 0) destroyLocked();
                return true;
            }
        }

        Lease acquire() {
            synchronized (monitor) {
                if (closed) throw new IllegalStateException("Vulkan buffer is closed");
                activeLeases++;
                return new Lease();
            }
        }

        /**
         * Releases a lease exactly once; repeated calls with the same lease are no-ops. {@code flush}
         * may be null for read-only or failed mappings.
         */
        void release(Lease lease, Runnable flush) {
            synchronized (monitor) {
                if (lease.released) return;
                lease.released = true;
                try {
                    if (flush != null && !destroyed) flush.run();
                } finally {
                    activeLeases--;
                    if (closed && activeLeases == 0) destroyLocked();
                }
            }
        }

        private void destroyLocked() {
            if (destroyed) return;
            destroyed = true;
            destroyer.run();
        }

        static final class Lease {
            private boolean released;

            private Lease() {
            }
        }
    }
}
