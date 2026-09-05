package com.asbestosstar.nativeaccelerator.nativeapi;

import java.lang.foreign.MemorySegment;

/** Stable Java-facing contract for the Native Accelerator C ABI. */
public interface NativeApi {
    int ABI_VERSION = 3;

    int abiVersion();
    long capabilities();
    String backendName();

    Endianness hostEndianness();
    short byteSwap16(short value);
    int byteSwap32(int value);
    long byteSwap64(long value);
    void byteSwap16Array(MemorySegment dst, MemorySegment src, long count);
    void byteSwap32Array(MemorySegment dst, MemorySegment src, long count);
    void byteSwap64Array(MemorySegment dst, MemorySegment src, long count);
    void xorBytes(MemorySegment dst, MemorySegment left, MemorySegment right, long length);

    /** Dense bit stream compatible with datafix PackedBitStorage (values may cross long boundaries). */
    void packedBitsUnpack(MemorySegment dstU32, MemorySegment srcU64, int bits, long count);
    void packedBitsPack(MemorySegment dstU64, MemorySegment srcU32, int bits, long count);
    void packedBitsRepack(MemorySegment dstU64, int dstBits, MemorySegment srcU64, int srcBits, long count);

    /** Runtime SimpleBitStorage layout (floor(64/bits) complete values per long; no crossing). */
    void simpleBitsUnpack(MemorySegment dstU32, MemorySegment srcU64, int bits, long count);
    void simpleBitsPack(MemorySegment dstU64, MemorySegment srcU32, int bits, long count);
    void simpleBitsRepack(MemorySegment dstU64, int dstBits, MemorySegment srcU64, int srcBits, long count);

    void decodeQuadCentroids(MemorySegment dstXyzF32, MemorySegment vertexData,
                             long vertexCount, int vertexStride, int positionOffset);
    void sortQuadIndicesDistance(MemorySegment dstQuadIndicesU32, MemorySegment centroidsXyzF32,
                                 long quadCount, float originX, float originY, float originZ);
    void sortQuadsDistanceWriteIndices(MemorySegment dstIndices, int indexBytes, MemorySegment vertexData,
                                       long vertexCount, int vertexStride, int positionOffset,
                                       float originX, float originY, float originZ);

    /** Numeric ARGB <-> ABGR channel swap; the operation is its own inverse. */
    void swizzleArgbAbgr(MemorySegment dstU32, MemorySegment srcU32, long pixelCount);
    void imageFillU32Rect(MemorySegment pixels, int imageWidth, int imageHeight,
                          int x, int y, int width, int height, int pixel);
    void imageCopyU32Rect(MemorySegment dstPixels, int dstWidth, int dstHeight, int dstX, int dstY,
                          MemorySegment srcPixels, int srcWidth, int srcHeight, int srcX, int srcY,
                          int width, int height, boolean swapX, boolean swapY);

    /** Minecraft-compatible Perlin kernel using the 256-byte GradientNoise permutation table. */
    void perlin3Batch(MemorySegment dstF32, MemorySegment xyzF64, long count, MemorySegment permutations256,
                      double offsetX, double offsetY, double offsetZ, boolean wrapCoordinates);
    void perlin3VolumeAdd(MemorySegment dstF32,
                          int sizeX, int sizeY, int sizeZ,
                          int minBlockX, int minBlockY, int minBlockZ,
                          int stepBlockX, int stepBlockY, int stepBlockZ,
                          double xzScale, double yScale, float amplitude,
                          MemorySegment permutations256,
                          double offsetX, double offsetY, double offsetZ,
                          boolean wrapCoordinates);
}
