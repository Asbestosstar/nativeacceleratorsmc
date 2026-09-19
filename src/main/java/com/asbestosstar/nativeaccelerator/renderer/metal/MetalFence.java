package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.commands.GpuFence;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.system.MemoryStack;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Real SDL GPU fence. Unlike the old implementation this does not idle the entire GPU. */
final class MetalFence implements GpuFence {
    private final MetalDevice device;
    private final AtomicBoolean closed = new AtomicBoolean();
    private long handle;
    private volatile boolean complete;

    MetalFence(MetalDevice device, long handle) {
        if (handle == 0L) throw new IllegalArgumentException("fence handle");
        this.device = device;
        this.handle = handle;
        MetalPerfCounters.fenceCreate();
    }

    @Override
    public boolean awaitCompletion(long timeoutNs) {
        if (closed.get() || complete) return true;
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
