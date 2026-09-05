package com.asbestosstar.nativeaccelerator.nativeapi;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Java 25 Foreign Function & Memory implementation of Native Accelerator ABI v3. */
public final class PanamaNativeApi implements NativeApi, AutoCloseable {
    private static final String LIBRARY = "nativeaccelerator";

    private final Arena libraryArena;
    private final MethodHandle abiVersion;
    private final MethodHandle capabilities;
    private final MethodHandle backendName;
    private final MethodHandle hostEndian;
    private final MethodHandle byteSwap16;
    private final MethodHandle byteSwap32;
    private final MethodHandle byteSwap64;
    private final MethodHandle byteSwap16Array;
    private final MethodHandle byteSwap32Array;
    private final MethodHandle byteSwap64Array;
    private final MethodHandle xorBytes;

    private final MethodHandle packedBitsUnpack;
    private final MethodHandle packedBitsPack;
    private final MethodHandle packedBitsRepack;
    private final MethodHandle simpleBitsUnpack;
    private final MethodHandle simpleBitsPack;
    private final MethodHandle simpleBitsRepack;
    private final MethodHandle decodeQuadCentroids;
    private final MethodHandle sortQuadIndicesDistance;
    private final MethodHandle sortQuadsDistanceWriteIndices;
    private final MethodHandle swizzleArgbAbgr;
    private final MethodHandle imageFillU32Rect;
    private final MethodHandle imageCopyU32Rect;
    private final MethodHandle perlin3Batch;
    private final MethodHandle perlin3VolumeAdd;

    private PanamaNativeApi(Arena libraryArena, SymbolLookup lookup) {
        Linker linker = Linker.nativeLinker();
        this.libraryArena = libraryArena;
        this.abiVersion = downcall(linker, lookup, "na_abi_version",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.capabilities = downcall(linker, lookup, "na_capabilities",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        this.backendName = downcall(linker, lookup, "na_backend_name",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        this.hostEndian = downcall(linker, lookup, "na_host_endian",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.byteSwap16 = downcall(linker, lookup, "na_bswap16",
                FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT));
        this.byteSwap32 = downcall(linker, lookup, "na_bswap32",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.byteSwap64 = downcall(linker, lookup, "na_bswap64",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        this.byteSwap16Array = downcall(linker, lookup, "na_bswap16_array",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        this.byteSwap32Array = downcall(linker, lookup, "na_bswap32_array",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        this.byteSwap64Array = downcall(linker, lookup, "na_bswap64_array",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        this.xorBytes = downcall(linker, lookup, "na_xor_bytes",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        this.packedBitsUnpack = statusCall(linker, lookup, "na_packed_bits_unpack_u32",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG);
        this.packedBitsPack = statusCall(linker, lookup, "na_packed_bits_pack_u32",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG);
        this.packedBitsRepack = statusCall(linker, lookup, "na_packed_bits_repack",
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG);
        this.simpleBitsUnpack = statusCall(linker, lookup, "na_simple_bits_unpack_u32",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG);
        this.simpleBitsPack = statusCall(linker, lookup, "na_simple_bits_pack_u32",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG);
        this.simpleBitsRepack = statusCall(linker, lookup, "na_simple_bits_repack",
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG);

        this.decodeQuadCentroids = statusCall(linker, lookup, "na_decode_quad_centroids",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
        this.sortQuadIndicesDistance = statusCall(linker, lookup, "na_sort_quad_indices_distance",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT);
        this.sortQuadsDistanceWriteIndices = statusCall(linker, lookup, "na_sort_quads_distance_write_indices",
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT);

        this.swizzleArgbAbgr = downcall(linker, lookup, "na_swizzle_argb_abgr_u32",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        this.imageFillU32Rect = statusCall(linker, lookup, "na_image_fill_u32_rect",
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
        this.imageCopyU32Rect = statusCall(linker, lookup, "na_image_copy_u32_rect",
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

        this.perlin3Batch = statusCall(linker, lookup, "na_perlin3_batch",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_INT);
        this.perlin3VolumeAdd = statusCall(linker, lookup, "na_perlin3_volume_add",
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_FLOAT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_INT);
    }

    public static PanamaNativeApi loadBundled() throws IOException {
        Path library = NativeLibraryLoader.extractBundledLibrary(LIBRARY);
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(library, arena);
            PanamaNativeApi api = new PanamaNativeApi(arena, lookup);
            int abi = api.abiVersion();
            if (abi != ABI_VERSION) {
                arena.close();
                throw new IOException("Native ABI mismatch: Java=" + ABI_VERSION + ", native=" + abi);
            }
            return api;
        } catch (Throwable t) {
            arena.close();
            if (t instanceof IOException ioe) throw ioe;
            throw new IOException("Unable to initialize Panama native backend", t);
        }
    }

    @Override public int abiVersion() { return invokeInt(abiVersion); }
    @Override public long capabilities() { return invokeLong(capabilities); }

    @Override
    public String backendName() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(128);
            long written = (long) backendName.invokeExact(buffer, 128L);
            if (written <= 0) return "native";
            int length = (int) Math.min(written, 127L);
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) bytes[i] = buffer.get(ValueLayout.JAVA_BYTE, i);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override public Endianness hostEndianness() { return Endianness.fromNative(invokeInt(hostEndian)); }
    @Override public short byteSwap16(short value) { try { return (short) byteSwap16.invokeExact(value); } catch (Throwable t) { throw rethrow(t); } }
    @Override public int byteSwap32(int value) { try { return (int) byteSwap32.invokeExact(value); } catch (Throwable t) { throw rethrow(t); } }
    @Override public long byteSwap64(long value) { try { return (long) byteSwap64.invokeExact(value); } catch (Throwable t) { throw rethrow(t); } }

    @Override public void byteSwap16Array(MemorySegment dst, MemorySegment src, long count) { invokeSwapArray(byteSwap16Array, dst, src, count, 2); }
    @Override public void byteSwap32Array(MemorySegment dst, MemorySegment src, long count) { invokeSwapArray(byteSwap32Array, dst, src, count, 4); }
    @Override public void byteSwap64Array(MemorySegment dst, MemorySegment src, long count) { invokeSwapArray(byteSwap64Array, dst, src, count, 8); }

    @Override
    public void xorBytes(MemorySegment dst, MemorySegment left, MemorySegment right, long length) {
        requireRange(dst, length, "XOR dst"); requireRange(left, length, "XOR left"); requireRange(right, length, "XOR right");
        try { xorBytes.invokeExact(dst, left, right, length); } catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void packedBitsUnpack(MemorySegment dst, MemorySegment src, int bits, long count) {
        validateBits(bits); requireRange(dst, mul(count, 4), "packed unpack dst"); requireRange(src, denseBytes(bits, count), "packed unpack src");
        invokeStatus(packedBitsUnpack, "packedBitsUnpack", dst, src, bits, count);
    }
    @Override public void packedBitsPack(MemorySegment dst, MemorySegment src, int bits, long count) {
        validateBits(bits); requireRange(dst, denseBytes(bits, count), "packed pack dst"); requireRange(src, mul(count, 4), "packed pack src");
        invokeStatus(packedBitsPack, "packedBitsPack", dst, src, bits, count);
    }
    @Override public void packedBitsRepack(MemorySegment dst, int dstBits, MemorySegment src, int srcBits, long count) {
        validateBits(srcBits); validateBits(dstBits); requireRange(dst, denseBytes(dstBits, count), "packed repack dst"); requireRange(src, denseBytes(srcBits, count), "packed repack src");
        invokeStatus(packedBitsRepack, "packedBitsRepack", dst, dstBits, src, srcBits, count);
    }

    @Override public void simpleBitsUnpack(MemorySegment dst, MemorySegment src, int bits, long count) {
        validateBits(bits); requireRange(dst, mul(count, 4), "simple unpack dst"); requireRange(src, simpleBytes(bits, count), "simple unpack src");
        invokeStatus(simpleBitsUnpack, "simpleBitsUnpack", dst, src, bits, count);
    }
    @Override public void simpleBitsPack(MemorySegment dst, MemorySegment src, int bits, long count) {
        validateBits(bits); requireRange(dst, simpleBytes(bits, count), "simple pack dst"); requireRange(src, mul(count, 4), "simple pack src");
        invokeStatus(simpleBitsPack, "simpleBitsPack", dst, src, bits, count);
    }
    @Override public void simpleBitsRepack(MemorySegment dst, int dstBits, MemorySegment src, int srcBits, long count) {
        validateBits(srcBits); validateBits(dstBits); requireRange(dst, simpleBytes(dstBits, count), "simple repack dst"); requireRange(src, simpleBytes(srcBits, count), "simple repack src");
        invokeStatus(simpleBitsRepack, "simpleBitsRepack", dst, dstBits, src, srcBits, count);
    }

    @Override
    public void decodeQuadCentroids(MemorySegment dst, MemorySegment vertexData, long vertexCount, int vertexStride, int positionOffset) {
        validateVertexRange(vertexData, vertexCount, vertexStride, positionOffset);
        requireRange(dst, mul(vertexCount / 4, 12), "quad centroid dst");
        invokeStatus(decodeQuadCentroids, "decodeQuadCentroids", dst, vertexData, vertexCount, vertexStride, positionOffset);
    }

    @Override
    public void sortQuadIndicesDistance(MemorySegment dst, MemorySegment centroids, long quadCount, float ox, float oy, float oz) {
        requireRange(dst, mul(quadCount, 4), "quad order dst"); requireRange(centroids, mul(quadCount, 12), "quad centroids");
        invokeStatus(sortQuadIndicesDistance, "sortQuadIndicesDistance", dst, centroids, quadCount, ox, oy, oz);
    }

    @Override
    public void sortQuadsDistanceWriteIndices(MemorySegment dst, int indexBytes, MemorySegment vertexData,
                                              long vertexCount, int vertexStride, int positionOffset,
                                              float ox, float oy, float oz) {
        if (indexBytes != 2 && indexBytes != 4) throw new IllegalArgumentException("indexBytes must be 2 or 4");
        validateVertexRange(vertexData, vertexCount, vertexStride, positionOffset);
        requireRange(dst, mul(vertexCount / 4, 6L * indexBytes), "sorted index dst");
        invokeStatus(sortQuadsDistanceWriteIndices, "sortQuadsDistanceWriteIndices",
                dst, indexBytes, vertexData, vertexCount, vertexStride, positionOffset, ox, oy, oz);
    }

    @Override
    public void swizzleArgbAbgr(MemorySegment dst, MemorySegment src, long pixelCount) {
        long bytes = mul(pixelCount, 4); requireRange(dst, bytes, "swizzle dst"); requireRange(src, bytes, "swizzle src");
        try { swizzleArgbAbgr.invokeExact(dst, src, pixelCount); } catch (Throwable t) { throw rethrow(t); }
    }

    @Override
    public void imageFillU32Rect(MemorySegment pixels, int imageWidth, int imageHeight,
                                 int x, int y, int width, int height, int pixel) {
        validateImage(pixels, imageWidth, imageHeight);
        invokeStatus(imageFillU32Rect, "imageFillU32Rect", pixels, imageWidth, imageHeight, x, y, width, height, pixel);
    }

    @Override
    public void imageCopyU32Rect(MemorySegment dst, int dstWidth, int dstHeight, int dstX, int dstY,
                                 MemorySegment src, int srcWidth, int srcHeight, int srcX, int srcY,
                                 int width, int height, boolean swapX, boolean swapY) {
        validateImage(dst, dstWidth, dstHeight); validateImage(src, srcWidth, srcHeight);
        invokeStatus(imageCopyU32Rect, "imageCopyU32Rect",
                dst, dstWidth, dstHeight, dstX, dstY,
                src, srcWidth, srcHeight, srcX, srcY, width, height, swapX ? 1 : 0, swapY ? 1 : 0);
    }

    @Override
    public void perlin3Batch(MemorySegment dst, MemorySegment xyz, long count, MemorySegment permutations,
                             double offsetX, double offsetY, double offsetZ, boolean wrapCoordinates) {
        requireRange(dst, mul(count, 4), "perlin dst"); requireRange(xyz, mul(count, 24), "perlin xyz"); requireRange(permutations, 256, "perlin permutation");
        invokeStatus(perlin3Batch, "perlin3Batch", dst, xyz, count, permutations, offsetX, offsetY, offsetZ, wrapCoordinates ? 1 : 0);
    }

    @Override
    public void perlin3VolumeAdd(MemorySegment dst,
                                 int sizeX, int sizeY, int sizeZ,
                                 int minBlockX, int minBlockY, int minBlockZ,
                                 int stepBlockX, int stepBlockY, int stepBlockZ,
                                 double xzScale, double yScale, float amplitude,
                                 MemorySegment permutations,
                                 double offsetX, double offsetY, double offsetZ,
                                 boolean wrapCoordinates) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) throw new IllegalArgumentException("Volume sizes must be positive");
        if (stepBlockX <= 0 || stepBlockY <= 0 || stepBlockZ <= 0) throw new IllegalArgumentException("Volume steps must be positive");
        long cells = mul(mul(sizeX, sizeY), sizeZ); requireRange(dst, mul(cells, 4), "perlin volume dst"); requireRange(permutations, 256, "perlin permutation");
        invokeStatus(perlin3VolumeAdd, "perlin3VolumeAdd",
                dst, sizeX, sizeY, sizeZ, minBlockX, minBlockY, minBlockZ,
                stepBlockX, stepBlockY, stepBlockZ, xzScale, yScale, amplitude,
                permutations, offsetX, offsetY, offsetZ, wrapCoordinates ? 1 : 0);
    }

    @Override public void close() { libraryArena.close(); }

    private static MethodHandle statusCall(Linker linker, SymbolLookup lookup, String symbol, java.lang.foreign.MemoryLayout... args) {
        return downcall(linker, lookup, symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT, args));
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        MemorySegment address = lookup.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError("Missing native symbol: " + symbol));
        return linker.downcallHandle(address, descriptor);
    }

    private static void invokeStatus(MethodHandle handle, String operation, Object... args) {
        try {
            int rc = (int) handle.invokeWithArguments(args);
            if (rc != 0) throw new IllegalArgumentException(operation + " rejected input (native status " + rc + ")");
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    private static int invokeInt(MethodHandle h) { try { return (int) h.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
    private static long invokeLong(MethodHandle h) { try { return (long) h.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }

    private static void invokeSwapArray(MethodHandle handle, MemorySegment dst, MemorySegment src, long count, long elementBytes) {
        long bytes = mul(count, elementBytes); requireRange(dst, bytes, "endian dst"); requireRange(src, bytes, "endian src");
        try { handle.invokeExact(dst, src, count); } catch (Throwable t) { throw rethrow(t); }
    }

    private static void validateBits(int bits) {
        if (bits < 1 || bits > 32) throw new IllegalArgumentException("bits must be 1..32, was " + bits);
    }

    private static long denseBytes(int bits, long count) {
        if (count < 0) throw new IllegalArgumentException("Negative count");
        long bitCount = mul(count, bits);
        return mul((Math.addExact(bitCount, 63) >>> 6), 8);
    }

    private static long simpleBytes(int bits, long count) {
        if (count < 0) throw new IllegalArgumentException("Negative count");
        long vpl = 64 / bits;
        return mul((Math.addExact(count, vpl - 1) / vpl), 8);
    }

    private static void validateVertexRange(MemorySegment data, long vertexCount, int stride, int positionOffset) {
        if (vertexCount < 0 || stride <= 0 || positionOffset < 0 || positionOffset + 12 > stride) throw new IllegalArgumentException("Invalid vertex layout");
        requireRange(data, mul(vertexCount, stride), "vertex data");
    }

    private static void validateImage(MemorySegment pixels, int width, int height) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("Negative image size");
        requireRange(pixels, mul(mul(width, height), 4), "image pixels");
    }

    private static void requireRange(MemorySegment segment, long bytes, String what) {
        if (bytes < 0 || bytes > segment.byteSize()) throw new IllegalArgumentException(what + " needs " + bytes + " bytes, has " + segment.byteSize());
        if (!segment.isNative()) throw new IllegalArgumentException(what + " must be native/direct memory for Panama downcall");
    }

    private static long mul(long a, long b) {
        if (a < 0 || b < 0) throw new IllegalArgumentException("Negative size");
        try { return Math.multiplyExact(a, b); } catch (ArithmeticException e) { throw new IllegalArgumentException("Size overflow", e); }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }
}
