package com.asbestosstar.nativeaccelerator.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import java.util.concurrent.locks.LockSupport;

/** Prevents chunk compiler workers from stealing CPU from the render/upload thread when staging is full. */
public final class ChunkRenderBackpressure {
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("renderer.chunkBackpressure", true);
    private static final long PARK_NANOS = Math.max(0L,
            NativeAcceleratorConfig.intValue("renderer.chunkBackpressureNanos", 50_000, 0));

    private ChunkRenderBackpressure() {}

    public static void pause() {
        Thread.onSpinWait();
        if (ENABLED && PARK_NANOS > 0L) LockSupport.parkNanos(PARK_NANOS);
    }
}
