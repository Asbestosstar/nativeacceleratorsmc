package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.commands.GpuFence;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.system.MemoryStack;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Metal fence. Mac1 can use Vulkan-style logical-submit fences; other paths retain native SDL fences. */
final class MetalFence implements GpuFence {
    private final MetalDevice device;
    private final MetalCommandEncoder logicalEncoder;
    private final long logicalSubmitIndex;
    private final AtomicBoolean closed = new AtomicBoolean();
    private long handle;
    private volatile boolean complete;

    MetalFence(MetalCommandEncoder encoder, long submitIndex) {
        this.device = encoder.device();
        this.logicalEncoder = encoder;
        this.logicalSubmitIndex = submitIndex;
        this.handle = 0L;
        MetalPerfCounters.fenceCreate();
    }

    MetalFence(MetalDevice device, long handle) {
        if (handle == 0L) throw new IllegalArgumentException("fence handle");
        this.device = device;
        this.logicalEncoder = null;
        this.logicalSubmitIndex = 0L;
        this.handle = handle;
        MetalPerfCounters.fenceCreate();
    }

    @Override
    public boolean awaitCompletion(long timeoutNs) {
        if (closed.get() || complete) return true;
        if (logicalEncoder != null) {
            complete = logicalEncoder.awaitLogicalFence(logicalSubmitIndex, timeoutNs);
            return complete;
        }

        MetalPerfCounters.fenceQuery();
        if (SDLGPU.SDL_QueryGPUFence(device.handle(), handle)) {
            complete = true;
            return true;
        }
        if (timeoutNs == 0L) return false;

        long start = MetalPerfCounters.tic();
        if (timeoutNs < 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer fences = stack.mallocPointer(1).put(0, handle);
                boolean ok = SDLGPU.SDL_WaitForGPUFences(device.handle(), true, fences);
                if (!ok) throw new IllegalStateException("SDL_WaitForGPUFences failed: " + MetalInterop.lastSdlError());
                complete = true;
            } finally {
                MetalPerfCounters.fenceWait(start);
            }
            return true;
        }

        long deadline = System.nanoTime() + timeoutNs;
        try {
            do {
                if (SDLGPU.SDL_QueryGPUFence(device.handle(), handle)) {
                    complete = true;
                    return true;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
                LockSupport.parkNanos(Math.min(remaining, 100_000L));
            } while (true);
        } finally {
            MetalPerfCounters.fenceWait(start);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            long fence = handle;
            handle = 0L;
            if (fence != 0L) SDLGPU.SDL_ReleaseGPUFence(device.handle(), fence);
        }
    }
}
