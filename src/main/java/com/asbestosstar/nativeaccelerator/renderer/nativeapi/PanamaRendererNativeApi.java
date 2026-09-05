package com.asbestosstar.nativeaccelerator.renderer.nativeapi;

import com.asbestosstar.nativeaccelerator.nativeapi.NativeLibraryLoader;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Java 25 FFM bridge for the independent native Vulkan-renderer ABI. */
public final class PanamaRendererNativeApi implements RendererNativeApi, AutoCloseable {
    private static final String LIBRARY = "nativeaccelerator_renderer_vulkan";

    private final Arena libraryArena;
    private final MethodHandle abiVersion;
    private final MethodHandle capabilities;
    private final MethodHandle backendName;
    private final MethodHandle vulkanAvailable;
    private final MethodHandle vulkanApiVersion;
    private final MethodHandle vulkanLoaderName;
    private final MethodHandle vulkanPhysicalDeviceCount;
    private final MethodHandle contextCreate;
    private final MethodHandle contextDestroy;
    private final MethodHandle contextWorkerCount;
    private final MethodHandle arenaCreate;
    private final MethodHandle arenaDestroy;
    private final MethodHandle arenaAlloc;
    private final MethodHandle arenaFree;
    private final MethodHandle sceneCreate;
    private final MethodHandle sceneDestroy;
    private final MethodHandle sceneCount;
    private final MethodHandle sceneUpsert;
    private final MethodHandle sceneRemove;
    private final MethodHandle sceneBuildIndirect;
    private final MethodHandle sceneExportGpu;
    private final MethodHandle voxelFaceMasks;
    private final MethodHandle voxelFaceMasksBatch;

    private PanamaRendererNativeApi(Arena libraryArena, SymbolLookup lookup) {
        Linker linker = Linker.nativeLinker();
        this.libraryArena = libraryArena;
        abiVersion = downcall(linker, lookup, "nar_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        capabilities = downcall(linker, lookup, "nar_capabilities", FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        backendName = downcall(linker, lookup, "nar_backend_name",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        vulkanAvailable = downcall(linker, lookup, "nar_vulkan_loader_available", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        vulkanApiVersion = downcall(linker, lookup, "nar_vulkan_loader_api_version", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        vulkanLoaderName = downcall(linker, lookup, "nar_vulkan_loader_name",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        vulkanPhysicalDeviceCount = downcall(linker, lookup, "nar_vulkan_physical_device_count",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        contextCreate = downcall(linker, lookup, "nar_context_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        contextDestroy = downcall(linker, lookup, "nar_context_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        contextWorkerCount = downcall(linker, lookup, "nar_context_worker_count",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        arenaCreate = downcall(linker, lookup, "nar_arena_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        arenaDestroy = downcall(linker, lookup, "nar_arena_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        arenaAlloc = statusCall(linker, lookup, "nar_arena_alloc",
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
        arenaFree = statusCall(linker, lookup, "nar_arena_free",
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
        sceneCreate = downcall(linker, lookup, "nar_scene_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        sceneDestroy = downcall(linker, lookup, "nar_scene_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        sceneCount = downcall(linker, lookup, "nar_scene_count",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        sceneUpsert = statusCall(linker, lookup, "nar_scene_upsert",
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
        sceneRemove = statusCall(linker, lookup, "nar_scene_remove", ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
        sceneBuildIndirect = downcall(linker, lookup, "nar_scene_build_indirect",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        sceneExportGpu = downcall(linker, lookup, "nar_scene_export_gpu",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        voxelFaceMasks = statusCall(linker, lookup, "nar_voxel_face_masks",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        voxelFaceMasksBatch = statusCall(linker, lookup, "nar_voxel_face_masks_batch",
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
    }

    public static PanamaRendererNativeApi loadBundled() throws IOException {
        Path library = NativeLibraryLoader.extractBundledLibrary(LIBRARY);
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(library, arena);
            PanamaRendererNativeApi api = new PanamaRendererNativeApi(arena, lookup);
            if (api.abiVersion() != ABI_VERSION) {
                throw new IOException("Renderer ABI mismatch: Java=" + ABI_VERSION + ", native=" + api.abiVersion());
            }
            return api;
        } catch (Throwable t) {
            arena.close();
            if (t instanceof IOException ioe) throw ioe;
            throw new IOException("Unable to initialize native Vulkan renderer bridge", t);
        }
    }

    @Override public int abiVersion() { return invokeInt(abiVersion); }
    @Override public long capabilities() { return invokeLong(capabilities); }
    @Override public String backendName() { return readString(backendName, "renderer-native"); }
    @Override public boolean vulkanLoaderAvailable() { return invokeInt(vulkanAvailable) != 0; }
    @Override public int vulkanLoaderApiVersion() { return invokeInt(vulkanApiVersion); }
    @Override public int vulkanPhysicalDeviceCount() { return invokeInt(vulkanPhysicalDeviceCount); }
    @Override public String vulkanLoaderName() { return readString(vulkanLoaderName, "unavailable"); }

    @Override public MemorySegment createContext(int workerCount) {
        if (workerCount < 0) throw new IllegalArgumentException("workerCount must be >= 0");
        try {
            MemorySegment handle = (MemorySegment)contextCreate.invokeExact(workerCount);
            if (handle.equals(MemorySegment.NULL)) throw new IllegalStateException("Unable to create native renderer context");
            return handle;
        } catch (Throwable t) { throw rethrow(t); }
    }
    @Override public void destroyContext(MemorySegment context) { invokeVoid(contextDestroy, requireHandle(context, "context")); }
    @Override public int contextWorkerCount(MemorySegment context) {
        try { return (int)contextWorkerCount.invokeExact(requireHandle(context, "context")); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public MemorySegment createArena(long capacity, long alignment) {
        if (capacity <= 0 || alignment < 0) throw new IllegalArgumentException("Invalid arena size/alignment");
        try {
            MemorySegment handle = (MemorySegment)arenaCreate.invokeExact(capacity, alignment);
            if (handle.equals(MemorySegment.NULL)) throw new IllegalStateException("Unable to create renderer arena");
            return handle;
        } catch (Throwable t) { throw rethrow(t); }
    }
    @Override public void destroyArena(MemorySegment arena) { invokeVoid(arenaDestroy, requireHandle(arena, "arena")); }
    @Override public long arenaAllocate(MemorySegment arena, long size, long alignment) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ValueLayout.JAVA_LONG);
            invokeStatus(arenaAlloc, "arenaAllocate", requireHandle(arena, "arena"), size, alignment, out);
            return out.get(ValueLayout.JAVA_LONG, 0);
        }
    }
    @Override public void arenaFree(MemorySegment arena, long offset, long size) {
        invokeStatus(arenaFree, "arenaFree", requireHandle(arena, "arena"), offset, size);
    }

    @Override public MemorySegment createScene(int initialCapacity) {
        if (initialCapacity < 0) throw new IllegalArgumentException("initialCapacity must be >= 0");
        try {
            MemorySegment handle = (MemorySegment)sceneCreate.invokeExact(initialCapacity);
            if (handle.equals(MemorySegment.NULL)) throw new IllegalStateException("Unable to create native scene");
            return handle;
        } catch (Throwable t) { throw rethrow(t); }
    }
    @Override public void destroyScene(MemorySegment scene) { invokeVoid(sceneDestroy, requireHandle(scene, "scene")); }
    @Override public int sceneCount(MemorySegment scene) {
        try { return (int)sceneCount.invokeExact(requireHandle(scene, "scene")); }
        catch (Throwable t) { throw rethrow(t); }
    }
    @Override public void sceneUpsert(MemorySegment scene, long key,
                                      float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                                      int firstIndex, int indexCount, int vertexOffset, int firstInstance, int materialMask) {
        invokeStatus(sceneUpsert, "sceneUpsert", requireHandle(scene, "scene"), key,
                minX, minY, minZ, maxX, maxY, maxZ,
                firstIndex, indexCount, vertexOffset, firstInstance, materialMask);
    }
    @Override public boolean sceneRemove(MemorySegment scene, long key) {
        try {
            int rc = (int)sceneRemove.invokeWithArguments(requireHandle(scene, "scene"), key);
            if (rc < 0) throw new IllegalArgumentException("sceneRemove rejected input (native status " + rc + ")");
            return rc == 0;
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public int sceneBuildIndirect(MemorySegment scene, MemorySegment planes,
                                            float cameraX, float cameraY, float cameraZ,
                                            float maxDistanceSquared, int materialMask,
                                            MemorySegment commands, MemorySegment keys, int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("capacity must be >= 0");
        MemorySegment planeArg = planes == null ? MemorySegment.NULL : planes;
        MemorySegment keyArg = keys == null ? MemorySegment.NULL : keys;
        if (planes != null) requireRange(planes, 24L * Float.BYTES, "frustum planes");
        requireRange(commands, (long)capacity * INDIRECT_COMMAND_BYTES, "indirect commands");
        if (keys != null) requireRange(keys, (long)capacity * Long.BYTES, "section keys");
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment outCount = scratch.allocate(ValueLayout.JAVA_INT);
            int rc;
            try {
                rc = (int)sceneBuildIndirect.invokeExact(requireHandle(scene, "scene"), planeArg,
                        cameraX, cameraY, cameraZ, maxDistanceSquared, materialMask,
                        commands, keyArg, capacity, outCount);
            } catch (Throwable t) { throw rethrow(t); }
            int visible = outCount.get(ValueLayout.JAVA_INT, 0);
            if (rc < 0) throw new IllegalArgumentException("sceneBuildIndirect failed (native status " + rc + ")");
            if (rc > 0) throw new IllegalStateException("Indirect output capacity " + capacity + " is smaller than visible count " + visible);
            return visible;
        }
    }

    @Override public int sceneExportGpu(MemorySegment scene, MemorySegment outBoundsMin, MemorySegment outBoundsMax,
                                        MemorySegment outDrawMeta, MemorySegment outKeys, int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("capacity must be >= 0");
        requireRange(outBoundsMin, (long)capacity * 16L, "GPU scene min bounds");
        requireRange(outBoundsMax, (long)capacity * 16L, "GPU scene max bounds");
        requireRange(outDrawMeta, (long)capacity * 20L, "GPU scene draw meta");
        MemorySegment keyArg = outKeys == null ? MemorySegment.NULL : outKeys;
        if (outKeys != null) requireRange(outKeys, (long)capacity * Long.BYTES, "GPU scene keys");
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment outCount = scratch.allocate(ValueLayout.JAVA_INT);
            int rc;
            try {
                rc = (int)sceneExportGpu.invokeExact(requireHandle(scene, "scene"), outBoundsMin, outBoundsMax,
                        outDrawMeta, keyArg, capacity, outCount);
            } catch (Throwable t) { throw rethrow(t); }
            int count = outCount.get(ValueLayout.JAVA_INT, 0);
            if (rc < 0) throw new IllegalArgumentException("sceneExportGpu failed (native status " + rc + ")");
            if (rc > 0) throw new IllegalStateException("GPU scene output capacity " + capacity + " is smaller than section count " + count);
            return count;
        }
    }

    @Override public void voxelFaceMasks(MemorySegment out, MemorySegment occupancy, MemorySegment neighbors) {
        requireRange(out, SECTION_VOLUME, "face masks");
        requireRange(occupancy, SECTION_VOLUME, "occupancy");
        MemorySegment neighborArg = neighbors == null ? MemorySegment.NULL : neighbors;
        if (neighbors != null) requireRange(neighbors, NEIGHBOR_PLANE_BYTES, "neighbor planes");
        invokeStatus(voxelFaceMasks, "voxelFaceMasks", out, occupancy, neighborArg);
    }

    @Override public void voxelFaceMasksBatch(MemorySegment context, MemorySegment out, MemorySegment occupancy,
                                              MemorySegment neighbors, int sectionCount) {
        if (sectionCount < 0) throw new IllegalArgumentException("sectionCount must be >= 0");
        long maskBytes = (long)sectionCount * SECTION_VOLUME;
        requireRange(out, maskBytes, "batch face masks");
        requireRange(occupancy, maskBytes, "batch occupancy");
        MemorySegment neighborArg = neighbors == null ? MemorySegment.NULL : neighbors;
        if (neighbors != null) requireRange(neighbors, (long)sectionCount * NEIGHBOR_PLANE_BYTES, "batch neighbor planes");
        invokeStatus(voxelFaceMasksBatch, "voxelFaceMasksBatch", requireHandle(context, "context"), out, occupancy, neighborArg, sectionCount);
    }

    @Override public void close() { libraryArena.close(); }

    private String readString(MethodHandle handle, String fallback) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(512);
            long written = (long)handle.invokeExact(buffer, 512L);
            if (written <= 0) return fallback;
            int length = (int)Math.min(written, 511L);
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; ++i) bytes[i] = buffer.get(ValueLayout.JAVA_BYTE, i);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable t) { throw rethrow(t); }
    }

    private static MethodHandle statusCall(Linker linker, SymbolLookup lookup, String symbol, java.lang.foreign.MemoryLayout... args) {
        return downcall(linker, lookup, symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT, args));
    }
    private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        MemorySegment address = lookup.find(symbol).orElseThrow(() -> new UnsatisfiedLinkError("Missing native renderer symbol: " + symbol));
        return linker.downcallHandle(address, descriptor);
    }
    private static void invokeStatus(MethodHandle handle, String operation, Object... args) {
        try {
            int rc = (int)handle.invokeWithArguments(args);
            if (rc != 0) throw new IllegalArgumentException(operation + " rejected input (native status " + rc + ")");
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }
    private static void invokeVoid(MethodHandle handle, MemorySegment arg) {
        try { handle.invokeExact(arg); } catch (Throwable t) { throw rethrow(t); }
    }
    private static int invokeInt(MethodHandle h) { try { return (int)h.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
    private static long invokeLong(MethodHandle h) { try { return (long)h.invokeExact(); } catch (Throwable t) { throw rethrow(t); } }
    private static MemorySegment requireHandle(MemorySegment handle, String what) {
        if (handle == null || handle.equals(MemorySegment.NULL)) throw new IllegalArgumentException(what + " handle is null");
        return handle;
    }
    private static void requireRange(MemorySegment segment, long bytes, String what) {
        if (segment == null || !segment.isNative() || bytes < 0 || segment.byteSize() < bytes) {
            throw new IllegalArgumentException(what + " must provide at least " + bytes + " native bytes");
        }
    }
    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }
}
