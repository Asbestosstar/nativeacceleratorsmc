package com.asbestosstar.nativeaccelerator.kernels;

import com.asbestosstar.nativeaccelerator.NativeAccelerator;
import com.asbestosstar.nativeaccelerator.nativeapi.Capabilities;
import com.asbestosstar.nativeaccelerator.nativeapi.NativeApi;
import com.asbestosstar.nativeaccelerator.worldgen.WorldgenProfiler;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * One-copy staging context for a complete Perlin NoiseStack.addToVolume invocation.
 *
 * <p>The caller enters once, redirects every Perlin layer into the same native float buffer, and exits
 * once. If a native layer unexpectedly fails, prior native results are committed to the heap and the
 * current/subsequent layers are executed by vanilla Java, preserving additive semantics.</p>
 */
public final class NoiseStackAcceleration {
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private NoiseStackAcceleration() {}

    public static boolean begin(float[] values, int logicalSize, int sizeX, int sizeY, int sizeZ) {
        State state = STATE.get();
        state.reset();
        if (!NoiseAcceleration.stackEnabled() || values == null) return false;
        if (!NoiseAcceleration.eligible(logicalSize, values.length, sizeX, sizeY, sizeZ)) return false;

        Optional<NativeApi> optional = NativeAccelerator.api();
        if (optional.isEmpty() || (optional.get().capabilities() & Capabilities.NOISE_KERNELS) == 0) return false;

        try {
            long cells = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
            long bytes = Math.multiplyExact(cells, Float.BYTES);
            if (bytes > Integer.MAX_VALUE) return false;
            state.ensure((int) bytes);
            state.heap = values;
            state.bytes = bytes;
            state.nativeValues = MemorySegment.ofBuffer(state.buffer).asSlice(0, bytes);
            state.profileStart = WorldgenProfiler.begin();
            state.profileCpuStart = WorldgenProfiler.beginCpu();
            state.cells = cells;
            state.nativeValues.copyFrom(MemorySegment.ofArray(values).asSlice(0, bytes));
            state.active = true;
            return true;
        } catch (Throwable ignored) {
            state.reset();
            return false;
        }
    }

    public static boolean addLayer(int sizeX, int sizeY, int sizeZ,
                                   int minBlockX, int minBlockY, int minBlockZ,
                                   int stepBlockX, int stepBlockY, int stepBlockZ,
                                   double xzScale, double yScale, float amplitude,
                                   byte[] permutations,
                                   double offsetX, double offsetY, double offsetZ,
                                   boolean wrapCoordinates) {
        State state = STATE.get();
        if (!state.active || state.nativeValues == null) return false;
        boolean ok = NativeKernelBridge.addPerlinVolumeDirect(state.nativeValues,
                sizeX, sizeY, sizeZ,
                minBlockX, minBlockY, minBlockZ,
                stepBlockX, stepBlockY, stepBlockZ,
                xzScale, yScale, amplitude,
                permutations, offsetX, offsetY, offsetZ, wrapCoordinates);
        if (!ok) failAndCommit();
        return ok;
    }

    public static boolean active() {
        return STATE.get().active;
    }

    public static void finish() {
        State state = STATE.get();
        try {
            if (state.active) commit(state);
        } finally {
            if (state.profileStart != 0L) {
                WorldgenProfiler.recordPhaseCpu(state.failed ? "noise.stack.partialFallback" : "noise.stack.accelerated",
                        state.profileStart, state.profileCpuStart, state.cells);
            }
            state.reset();
        }
    }

    /** Commit successful native layers, then leave the rest of the stack to vanilla Java. */
    public static void failAndCommit() {
        State state = STATE.get();
        if (!state.active) return;
        try {
            commit(state);
        } finally {
            state.failed = true;
            state.active = false;
        }
    }

    private static void commit(State state) {
        if (state.heap != null && state.nativeValues != null && state.bytes > 0) {
            MemorySegment.ofArray(state.heap).asSlice(0, state.bytes).copyFrom(state.nativeValues.asSlice(0, state.bytes));
        }
    }

    private static final class State {
        ByteBuffer buffer = ByteBuffer.allocateDirect(0);
        MemorySegment nativeValues;
        float[] heap;
        long bytes;
        boolean active;
        boolean failed;
        long profileStart;
        long profileCpuStart = -1L;
        long cells;

        void ensure(int bytes) {
            if (buffer.capacity() < bytes) {
                int capacity = 1;
                while (capacity < bytes && capacity > 0) capacity <<= 1;
                if (capacity <= 0) capacity = bytes;
                buffer = ByteBuffer.allocateDirect(capacity);
            }
            buffer.clear();
        }

        void reset() {
            nativeValues = null;
            heap = null;
            bytes = 0;
            active = false;
            failed = false;
            profileStart = 0L;
            profileCpuStart = -1L;
            cells = 0L;
        }
    }
}
