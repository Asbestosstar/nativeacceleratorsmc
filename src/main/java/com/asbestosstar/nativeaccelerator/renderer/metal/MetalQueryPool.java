package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.OptionalLong;

/** CPU-clock timestamp fallback. It preserves RenderPearl's query semantics on SDL GPU 3.2/3.4. */
final class MetalQueryPool implements GpuQueryPool {
    private final long[] values;
    private final boolean[] written;
    MetalQueryPool(int size) { if (size < 1) throw new IllegalArgumentException("size"); values = new long[size]; written = new boolean[size]; }
    void write(int index) { if (index < 0 || index >= values.length) throw new IndexOutOfBoundsException(index); values[index] = System.nanoTime(); written[index] = true; }
    @Override public int size() { return values.length; }
    @Override public OptionalLong getValue(int index) { return written[index] ? OptionalLong.of(values[index]) : OptionalLong.empty(); }
    @Override public OptionalLong[] getValues(int first, int count) { OptionalLong[] out = new OptionalLong[count]; for (int i=0;i<count;i++) out[i]=getValue(first+i); return out; }
    @Override public void close() { }
}

