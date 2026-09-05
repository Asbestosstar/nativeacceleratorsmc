package com.asbestosstar.nativeaccelerator.kernels;

import com.asbestosstar.nativeaccelerator.NativeAccelerator;
import com.asbestosstar.nativeaccelerator.nativeapi.Capabilities;
import com.asbestosstar.nativeaccelerator.nativeapi.NativeApi;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Loader/Minecraft-neutral helpers for feeding common JVM data shapes into the native ABI.
 *
 * Direct/native memory is passed without copying. Heap-array helpers use reusable thread-local
 * direct staging buffers because ordinary (non-critical) Panama downcalls cannot safely expose
 * movable Java heap arrays to long-running native kernels.
 */
public final class NativeKernelBridge {
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private NativeKernelBridge() {}

    public static boolean supports(long capability) {
        return NativeAccelerator.api().map(api -> (api.capabilities() & capability) != 0).orElse(false);
    }

    /** Native-accelerated equivalent of SimpleBitStorage.unpack(int[]), with staging copies. */
    public static boolean unpackSimpleBitStorage(long[] packed, int bits, int size, int[] output) {
        if (packed == null || output == null || size < 0 || output.length < size) return false;
        Optional<NativeApi> optional = NativeAccelerator.api();
        if (optional.isEmpty() || (optional.get().capabilities() & Capabilities.PACKED_BITS) == 0) return false;

        int valuesPerLong = 64 / bits;
        int packedLongs = (size + valuesPerLong - 1) / valuesPerLong;
        if (packed.length < packedLongs) return false;
        long srcBytes = Math.multiplyExact((long) packedLongs, Long.BYTES);
        long dstBytes = Math.multiplyExact((long) size, Integer.BYTES);

        try {
            Scratch scratch = SCRATCH.get();
            MemorySegment src = scratch.first(srcBytes);
            MemorySegment dst = scratch.second(dstBytes);
            src.asSlice(0, srcBytes).copyFrom(MemorySegment.ofArray(packed).asSlice(0, srcBytes));
            optional.get().simpleBitsUnpack(dst, src, bits, size);
            MemorySegment.ofArray(output).asSlice(0, dstBytes).copyFrom(dst.asSlice(0, dstBytes));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Dense/cross-long PackedBitStorage unpack helper, primarily for DataFixer paths. */
    public static boolean unpackDensePackedBits(long[] packed, int bits, int size, int[] output) {
        if (packed == null || output == null || size < 0 || output.length < size) return false;
        Optional<NativeApi> optional = NativeAccelerator.api();
        if (optional.isEmpty() || (optional.get().capabilities() & Capabilities.PACKED_BITS) == 0) return false;

        long packedLongs = (Math.addExact(Math.multiplyExact((long) size, bits), 63) >>> 6);
        if (packed.length < packedLongs) return false;
        long srcBytes = Math.multiplyExact(packedLongs, Long.BYTES);
        long dstBytes = Math.multiplyExact((long) size, Integer.BYTES);

        try {
            Scratch scratch = SCRATCH.get();
            MemorySegment src = scratch.first(srcBytes);
            MemorySegment dst = scratch.second(dstBytes);
            src.asSlice(0, srcBytes).copyFrom(MemorySegment.ofArray(packed).asSlice(0, srcBytes));
            optional.get().packedBitsUnpack(dst, src, bits, size);
            MemorySegment.ofArray(output).asSlice(0, dstBytes).copyFrom(dst.asSlice(0, dstBytes));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** NativeImage-style 32-bit fill. pixelArgb is converted to Minecraft's ABGR integer form. */
    public static boolean fillNativeImage(long pixelAddress, int imageWidth, int imageHeight,
                                          int x, int y, int width, int height, int pixelArgb) {
        Optional<NativeApi> optional = NativeAccelerator.api();
        if (pixelAddress == 0 || optional.isEmpty() || (optional.get().capabilities() & Capabilities.IMAGE_KERNELS) == 0) return false;
        try {
            long bytes = Math.multiplyExact(Math.multiplyExact((long) imageWidth, imageHeight), 4L);
            MemorySegment pixels = MemorySegment.ofAddress(pixelAddress).reinterpret(bytes);
            optional.get().imageFillU32Rect(pixels, imageWidth, imageHeight, x, y, width, height, swapRedBlue(pixelArgb));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** NativeImage self-copy equivalent for the primitive-argument copyRect overload. */
    public static boolean copyNativeImageRect(long pixelAddress, int imageWidth, int imageHeight,
                                              int sourceX, int sourceY, int targetX, int targetY,
                                              int width, int height, boolean swapX, boolean swapY) {
        Optional<NativeApi> optional = NativeAccelerator.api();
        if (pixelAddress == 0 || optional.isEmpty() || (optional.get().capabilities() & Capabilities.IMAGE_KERNELS) == 0) return false;
        try {
            long bytes = Math.multiplyExact(Math.multiplyExact((long) imageWidth, imageHeight), 4L);
            MemorySegment pixels = MemorySegment.ofAddress(pixelAddress).reinterpret(bytes);
            optional.get().imageCopyU32Rect(pixels, imageWidth, imageHeight, targetX, targetY,
                    pixels, imageWidth, imageHeight, sourceX, sourceY, width, height, swapX, swapY);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Same bit operation used by ARGB.toABGR/fromABGR for 0xAARRGGBB/0xAABBGGRR. */
    public static int swapRedBlue(int value) {
        return (value & 0xFF00FF00) | ((value & 0x00FF0000) >>> 16) | ((value & 0x000000FF) << 16);
    }

    private static final class Scratch {
        private ByteBuffer first = ByteBuffer.allocateDirect(0);
        private ByteBuffer second = ByteBuffer.allocateDirect(0);

        MemorySegment first(long bytes) {
            first = ensure(first, bytes);
            return MemorySegment.ofBuffer(first);
        }

        MemorySegment second(long bytes) {
            second = ensure(second, bytes);
            return MemorySegment.ofBuffer(second);
        }

        private static ByteBuffer ensure(ByteBuffer existing, long requested) {
            if (requested > Integer.MAX_VALUE) throw new IllegalArgumentException("Scratch buffer exceeds Java direct ByteBuffer limit");
            int bytes = (int) requested;
            if (existing.capacity() >= bytes) {
                existing.clear();
                return existing;
            }
            int capacity = 1;
            while (capacity < bytes && capacity > 0) capacity <<= 1;
            if (capacity <= 0) capacity = bytes;
            return ByteBuffer.allocateDirect(capacity);
        }
    }
}
