package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.commands.GpuFence;
import java.util.concurrent.atomic.AtomicBoolean;

/** Conservative fence backed by a device-idle wait at creation time. */
final class MetalFence implements GpuFence {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final boolean complete;
    MetalFence(MetalDevice device) {
        Object result = MetalInterop.sdlCall("SDL_WaitForGPUIdle", device.handle());
        complete = !(result instanceof Boolean b) || b;
    }
    @Override public boolean awaitCompletion(long timeout) { return !closed.get() && complete; }
    @Override public void close() { closed.set(true); }
}
