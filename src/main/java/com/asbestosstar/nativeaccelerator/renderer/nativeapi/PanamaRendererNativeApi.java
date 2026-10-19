package com.asbestosstar.nativeaccelerator.renderer.nativeapi;

import com.asbestosstar.nativeaccelerator.nativeapi.NativeLibraryLoader;
import com.asbestosstar.nativeaccelerator.nativeapi.PanamaDiagnostics;

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
    private final MethodHandle rendererPlatformRole;
    private final MethodHandle rendererClientEligible;
    private final MethodHandle rendererPlatformName;
    private final MethodHandle rendererPlatformEvidence;
    private final MethodHandle rendererRoleOverride;
    private final MethodHandle rendererOptionsPathSet;
    private final MethodHandle rendererOptionsPathGet;
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

    // Optional Vulkan session ABI symbols. Each is null when the loaded library
    // predates NAR_VULKAN_ABI_VERSION; vulkanSessionSupported() reflects that.
    private final MethodHandle vulkanQueryCapabilities;
    private final MethodHandle vulkanSessionCreate;
    private final MethodHandle vulkanSessionDestroy;
    private final MethodHandle vulkanSessionTier;
    private final MethodHandle vulkanSessionFeatures;
    private final MethodHandle vulkanSessionLastError;
    private final MethodHandle vulkanMesaPrefix;
    private final MethodHandle vulkanMesaLoader;
    private final boolean vulkanSessionSupported;

    private final MethodHandle vulkanSessionCreatePresent;
    private final MethodHandle vulkanWsiQuery;
    private final MethodHandle vulkanWsiReportSession;
    private final MethodHandle vulkanWsiLastError;
    private final MethodHandle vulkanSwapchainCreate;
    private final MethodHandle vulkanSwapchainDestroy;
    private final MethodHandle vulkanSwapchainRecreate;
    private final MethodHandle vulkanSwapchainAcquire;
    private final MethodHandle vulkanSwapchainPresent;
    private final boolean vulkanWsiSupported;

    // Optional offscreen image/render/readback ABI symbols.
    private final MethodHandle vulkanOffscreenImageCreate;
    private final MethodHandle vulkanOffscreenImageDestroy;
    private final MethodHandle vulkanOffscreenRenderBegin;
    private final MethodHandle vulkanOffscreenRenderEnd;
    private final MethodHandle vulkanOffscreenReadback;
    private final boolean vulkanOffscreenSupported;

    // Optional Vulkan buffer resource ABI symbols.
    private final MethodHandle vulkanBufferCreate;
    private final MethodHandle vulkanBufferDestroy;
    private final MethodHandle vulkanBufferUpload;
    private final MethodHandle vulkanBufferSize;
    private final boolean vulkanBufferSupported;

    // Optional shader module + graphics pipeline + command recording ABI symbols.
    private final MethodHandle vulkanShaderModuleCreate;
    private final MethodHandle vulkanShaderModuleDestroy;
    private final MethodHandle vulkanPipelineCreate;
    private final MethodHandle vulkanPipelineDestroy;
    private final MethodHandle vulkanCmdBindPipeline;
    private final MethodHandle vulkanCmdBindVertexBuffer;
    private final MethodHandle vulkanCmdDraw;
    private final boolean vulkanPipelineSupported;

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
        rendererPlatformRole = downcall(linker, lookup, "nar_renderer_platform_role",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        rendererClientEligible = downcall(linker, lookup, "nar_renderer_client_eligible",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        rendererPlatformName = downcall(linker, lookup, "nar_renderer_platform_name",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        rendererPlatformEvidence = downcall(linker, lookup, "nar_renderer_platform_evidence",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        rendererRoleOverride = downcall(linker, lookup, "nar_renderer_set_role_override",
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
        rendererOptionsPathSet = downcall(linker, lookup, "nar_renderer_set_options_path",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        rendererOptionsPathGet = downcall(linker, lookup, "nar_renderer_options_path",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
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

        // Optional session ABI — bind only when the symbols are present.
        vulkanQueryCapabilities = optDowncall(linker, lookup, "nar_vulkan_query_capabilities",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        vulkanSessionCreate = optDowncall(linker, lookup, "nar_vulkan_session_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        vulkanSessionDestroy = optDowncall(linker, lookup, "nar_vulkan_session_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        vulkanSessionTier = optDowncall(linker, lookup, "nar_vulkan_session_tier",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        vulkanSessionFeatures = optDowncall(linker, lookup, "nar_vulkan_session_features",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        vulkanSessionLastError = optDowncall(linker, lookup, "nar_vulkan_session_last_error",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        vulkanMesaPrefix = optDowncall(linker, lookup, "nar_vulkan_mesa_prefix",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        vulkanMesaLoader = optDowncall(linker, lookup, "nar_vulkan_mesa_loader",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        vulkanSessionSupported = vulkanSessionCreate != null && vulkanSessionDestroy != null
                && vulkanSessionTier != null && vulkanSessionFeatures != null
                && vulkanSessionLastError != null;
        vulkanSessionCreatePresent = optDowncall(linker, lookup, "nar_vulkan_session_create_present",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        vulkanWsiQuery = optDowncall(linker, lookup, "nar_vulkan_wsi_supported",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        vulkanWsiReportSession = optDowncall(linker, lookup, "nar_vulkan_wsi_report_session",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        vulkanWsiLastError = optDowncall(linker, lookup, "nar_vulkan_wsi_last_error",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        vulkanSwapchainCreate = optDowncall(linker, lookup, "nar_vulkan_swapchain_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        vulkanSwapchainDestroy = optDowncall(linker, lookup, "nar_vulkan_swapchain_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        vulkanSwapchainRecreate = optDowncall(linker, lookup, "nar_vulkan_swapchain_recreate",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        vulkanSwapchainAcquire = optDowncall(linker, lookup, "nar_vulkan_swapchain_acquire",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        vulkanSwapchainPresent = optDowncall(linker, lookup, "nar_vulkan_swapchain_present",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        vulkanWsiSupported = vulkanSessionCreatePresent != null && vulkanWsiQuery != null
                && vulkanWsiReportSession != null && vulkanWsiLastError != null
                && vulkanSwapchainCreate != null && vulkanSwapchainDestroy != null
                && vulkanSwapchainRecreate != null && vulkanSwapchainAcquire != null
                && vulkanSwapchainPresent != null;
        // Optional offscreen image/render/readback ABI — absent in pre-offscreen builds.
        vulkanOffscreenImageCreate = optDowncall(linker, lookup, "nar_vulkan_offscreen_image_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        vulkanOffscreenImageDestroy = optDowncall(linker, lookup, "nar_vulkan_offscreen_image_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        vulkanOffscreenRenderBegin = optDowncall(linker, lookup, "nar_vulkan_offscreen_render_begin",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        vulkanOffscreenRenderEnd = optDowncall(linker, lookup, "nar_vulkan_offscreen_render_end",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        vulkanOffscreenReadback = optDowncall(linker, lookup, "nar_vulkan_offscreen_readback",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        vulkanOffscreenSupported = vulkanOffscreenImageCreate != null && vulkanOffscreenImageDestroy != null
                && vulkanOffscreenRenderBegin != null && vulkanOffscreenRenderEnd != null
                && vulkanOffscreenReadback != null;
        // Optional Vulkan buffer resource ABI — absent in pre-buffer builds.
        vulkanBufferCreate = optDowncall(linker, lookup, "nar_vulkan_buffer_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        vulkanBufferDestroy = optDowncall(linker, lookup, "nar_vulkan_buffer_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        vulkanBufferUpload = optDowncall(linker, lookup, "nar_vulkan_buffer_upload",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        vulkanBufferSize = optDowncall(linker, lookup, "nar_vulkan_buffer_size",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        vulkanBufferSupported = vulkanBufferCreate != null && vulkanBufferDestroy != null
                && vulkanBufferUpload != null && vulkanBufferSize != null;
        // Optional shader module + graphics pipeline + command recording ABI.
        vulkanShaderModuleCreate = optDowncall(linker, lookup, "nar_vulkan_shader_module_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        vulkanShaderModuleDestroy = optDowncall(linker, lookup, "nar_vulkan_shader_module_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        vulkanPipelineCreate = optDowncall(linker, lookup, "nar_vulkan_pipeline_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        vulkanPipelineDestroy = optDowncall(linker, lookup, "nar_vulkan_pipeline_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        vulkanCmdBindPipeline = optDowncall(linker, lookup, "nar_vulkan_cmd_bind_pipeline",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        vulkanCmdBindVertexBuffer = optDowncall(linker, lookup, "nar_vulkan_cmd_bind_vertex_buffer",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        vulkanCmdDraw = optDowncall(linker, lookup, "nar_vulkan_cmd_draw",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        vulkanPipelineSupported = vulkanShaderModuleCreate != null && vulkanShaderModuleDestroy != null
                && vulkanPipelineCreate != null && vulkanPipelineDestroy != null
                && vulkanCmdBindPipeline != null && vulkanCmdBindVertexBuffer != null
                && vulkanCmdDraw != null;
    }

    public static PanamaRendererNativeApi loadBundled() throws IOException {
        Path library;
        try {
            library = NativeLibraryLoader.extractBundledLibrary(LIBRARY);
        } catch (Throwable t) {
            throw new IOException("Unable to initialize native Vulkan renderer bridge", t);
        }
        Arena arena = Arena.ofShared();
        PanamaDiagnostics.LoadStage stage = new PanamaDiagnostics.LoadStage();
        try {
            stage.set(PanamaDiagnostics.Stage.LIBRARY_LOOKUP);
            SymbolLookup lookup = SymbolLookup.libraryLookup(library, arena);
            stage.set(PanamaDiagnostics.Stage.SYMBOL_BINDING);
            PanamaRendererNativeApi api = new PanamaRendererNativeApi(arena, lookup);
            stage.set(PanamaDiagnostics.Stage.ABI_PROBE);
            int abi = api.abiVersion();
            stage.set(PanamaDiagnostics.Stage.ABI_VALIDATION);
            if (abi != ABI_VERSION) {
                throw new IOException("Renderer ABI mismatch: Java=" + ABI_VERSION + ", native=" + abi);
            }
            return api;
        } catch (Throwable t) {
            try {
                arena.close();
            } catch (Throwable closeFailure) {
                t.addSuppressed(closeFailure);
            }
            if (t instanceof IOException ioe) throw ioe;
            throw new IOException("Unable to initialize native Vulkan renderer bridge (stage=" + stage.value() + ")", t);
        }
    }

    @Override public int abiVersion() { return invokeInt(abiVersion); }
    @Override public long capabilities() { return invokeLong(capabilities); }
    @Override public String backendName() { return readString(backendName, "renderer-native"); }
    @Override public boolean vulkanLoaderAvailable() { return invokeInt(vulkanAvailable) != 0; }
    @Override public int vulkanLoaderApiVersion() { return invokeInt(vulkanApiVersion); }
    @Override public int vulkanPhysicalDeviceCount() { return invokeInt(vulkanPhysicalDeviceCount); }
    @Override public String vulkanLoaderName() { return readString(vulkanLoaderName, "unavailable"); }

    @Override public int rendererPlatformRole() { return invokeInt(rendererPlatformRole); }
    @Override public boolean rendererClientEligible() { return invokeInt(rendererClientEligible) != 0; }
    @Override public int rendererPlatformEvidence() { return invokeInt(rendererPlatformEvidence); }
    @Override public String rendererPlatformName() { return readString(rendererPlatformName, "unknown"); }
    @Override public void setRendererRoleOverride(int role) {
        if (role != ROLE_AUTO && role != ROLE_CLIENT && role != ROLE_SERVER_ONLY) {
            throw new IllegalArgumentException("role must be ROLE_AUTO, ROLE_CLIENT or ROLE_SERVER_ONLY");
        }
        invokeVoid(rendererRoleOverride, role);
    }
    @Override public void setMinecraftOptionsPath(String path) {
        if (path == null || path.isEmpty()) {
            invokeVoidAddress(rendererOptionsPathSet, MemorySegment.NULL);
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            invokeVoidAddress(rendererOptionsPathSet, allocateUtf8(arena, path));
        }
    }
    @Override public String minecraftOptionsPath() { return readString(rendererOptionsPathGet, ""); }
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

    // Optional Vulkan session ABI overrides.

    @Override public boolean vulkanSessionSupported() { return vulkanSessionSupported; }

    @Override public MemorySegment vulkanSessionCreate(int mode, int width, int height) {
        requireSessionAbi("vulkanSessionCreate");
        if (mode < VULKAN_MODE_AUTO || mode > VULKAN_MODE_PRESENT) throw new IllegalArgumentException("invalid Vulkan session mode");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Vulkan session dimensions must be positive");
        try {
            MemorySegment handle = (MemorySegment)vulkanSessionCreate.invokeExact(mode, width, height);
            if (handle == null || handle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("nar_vulkan_session_create returned null — Vulkan unavailable");
            }
            return handle;
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void vulkanSessionDestroy(MemorySegment session) {
        requireSessionAbi("vulkanSessionDestroy");
        try { vulkanSessionDestroy.invokeExact(requireHandle(session, "vulkan session")); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public int vulkanSessionTier(MemorySegment session) {
        requireSessionAbi("vulkanSessionTier");
        try { return (int)vulkanSessionTier.invokeExact(requireHandle(session, "vulkan session")); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public long vulkanSessionFeatures(MemorySegment session) {
        requireSessionAbi("vulkanSessionFeatures");
        try { return (long)vulkanSessionFeatures.invokeExact(requireHandle(session, "vulkan session")); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public boolean vulkanWsiSupported() { return vulkanWsiSupported; }

    @Override public MemorySegment vulkanSessionCreatePresent(int platform, long nativeDisplay, long nativeWindow,
                                                               int flags, int width, int height) {
        requireWsiAbi("vulkanSessionCreatePresent");
        if (width <= 0 || height <= 0 || nativeWindow == 0) throw new IllegalArgumentException("invalid WSI window dimensions/handle");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment descriptor = arena.allocate(48, 8);
            descriptor.set(ValueLayout.JAVA_INT, 0, 48);
            descriptor.set(ValueLayout.JAVA_INT, 4, VULKAN_WSI_ABI_VERSION);
            descriptor.set(ValueLayout.JAVA_INT, 8, platform);
            descriptor.set(ValueLayout.JAVA_INT, 12, flags);
            descriptor.set(ValueLayout.JAVA_LONG, 16, nativeDisplay);
            descriptor.set(ValueLayout.JAVA_LONG, 24, nativeWindow);
            descriptor.set(ValueLayout.JAVA_INT, 32, width);
            descriptor.set(ValueLayout.JAVA_INT, 36, height);
            MemorySegment handle = (MemorySegment)vulkanSessionCreatePresent.invokeExact(descriptor, width, height);
            if (handle == null || handle.equals(MemorySegment.NULL)) throw new IllegalStateException("native present session creation failed");
            return handle;
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public int vulkanWsiSupported(int platform) {
        requireWsiAbi("vulkanWsiSupported");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment report = arena.allocate(436, 8);
            report.set(ValueLayout.JAVA_INT, 0, 436);
            report.set(ValueLayout.JAVA_INT, 4, VULKAN_WSI_ABI_VERSION);
            int rc = (int)vulkanWsiQuery.invokeExact(platform, report);
            if (rc != 0) return rc;
            return report.get(ValueLayout.JAVA_INT, 12);
        } catch (Throwable t) { throw rethrow(t); }
    }

    @Override public MemorySegment vulkanSwapchainCreate(MemorySegment session, int width, int height) {
        requireWsiAbi("vulkanSwapchainCreate");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("swapchain dimensions must be positive");
        try {
            MemorySegment handle = (MemorySegment)vulkanSwapchainCreate.invokeExact(requireHandle(session, "vulkan session"), width, height);
            if (handle == null || handle.equals(MemorySegment.NULL)) throw new IllegalStateException("native swapchain creation failed");
            return handle;
        } catch (Throwable t) { throw rethrow(t); }
    }
    @Override public void vulkanSwapchainDestroy(MemorySegment session, MemorySegment swapchain) {
        requireWsiAbi("vulkanSwapchainDestroy");
        try { vulkanSwapchainDestroy.invokeExact(requireHandle(session, "vulkan session"), requireHandle(swapchain, "swapchain")); }
        catch (Throwable t) { throw rethrow(t); }
    }
    @Override public int vulkanSwapchainRecreate(MemorySegment session, MemorySegment swapchain, int width, int height) {
        requireWsiAbi("vulkanSwapchainRecreate");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("swapchain dimensions must be positive");
        try { return (int)vulkanSwapchainRecreate.invokeExact(requireHandle(session, "vulkan session"), requireHandle(swapchain, "swapchain"), width, height); }
        catch (Throwable t) { throw rethrow(t); }
    }
    @Override public int vulkanSwapchainAcquire(MemorySegment session, MemorySegment swapchain, MemorySegment outImageIndex) {
        requireWsiAbi("vulkanSwapchainAcquire");
        requireRange(outImageIndex, 4, "swapchain image index");
        try { return (int)vulkanSwapchainAcquire.invokeExact(requireHandle(session, "vulkan session"), requireHandle(swapchain, "swapchain"), outImageIndex); }
        catch (Throwable t) { throw rethrow(t); }
    }
    @Override public int vulkanSwapchainPresent(MemorySegment session, MemorySegment swapchain, int imageIndex) {
        requireWsiAbi("vulkanSwapchainPresent");
        try { return (int)vulkanSwapchainPresent.invokeExact(requireHandle(session, "vulkan session"), requireHandle(swapchain, "swapchain"), imageIndex); }
        catch (Throwable t) { throw rethrow(t); }
    }

    private void requireWsiAbi(String method) {
        if (!vulkanWsiSupported) throw new UnsupportedOperationException(method + ": Vulkan WSI ABI not present in this library build");
    }

    @Override public String vulkanSessionLastError(MemorySegment session) {
        requireSessionAbi("vulkanSessionLastError");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(512);
            // The native function accepts NULL to query length; pass the actual session handle
            // (may be NULL for the "no session" static message — native is safe with it).
            MemorySegment sessionArg = session == null ? MemorySegment.NULL : session;
            long written = (long)vulkanSessionLastError.invokeExact(sessionArg, buffer, 512L);
            if (written <= 0) return "";
            int length = (int)Math.min(written, 511L);
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; ++i) bytes[i] = buffer.get(ValueLayout.JAVA_BYTE, i);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable t) { throw rethrow(t); }
    }

    @Override public String vulkanMesaPrefix() {
        if (vulkanMesaPrefix == null) return "";
        return readString(vulkanMesaPrefix, "");
    }

    @Override public String vulkanMesaLoader() {
        if (vulkanMesaLoader == null) return "";
        return readString(vulkanMesaLoader, "");
    }

    // Optional offscreen image/render/readback ABI overrides.

    @Override public boolean vulkanOffscreenSupported() { return vulkanOffscreenSupported; }

    @Override public MemorySegment vulkanOffscreenImageCreate(MemorySegment session, int width, int height) {
        requireOffscreenAbi("vulkanOffscreenImageCreate");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("offscreen dimensions must be positive");
        try {
            MemorySegment handle = (MemorySegment)vulkanOffscreenImageCreate.invokeExact(
                    requireHandle(session, "vulkan session"), width, height);
            if (handle == null || handle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("nar_vulkan_offscreen_image_create returned null");
            }
            return handle;
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void vulkanOffscreenImageDestroy(MemorySegment session, MemorySegment image) {
        requireOffscreenAbi("vulkanOffscreenImageDestroy");
        try {
            vulkanOffscreenImageDestroy.invokeExact(
                    requireHandle(session, "vulkan session"), requireHandle(image, "offscreen image"));
        } catch (Throwable t) { throw rethrow(t); }
    }

    @Override public MemorySegment vulkanOffscreenRenderBegin(MemorySegment session, MemorySegment image) {
        requireOffscreenAbi("vulkanOffscreenRenderBegin");
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment outCommandBuffer = scratch.allocate(ValueLayout.ADDRESS);
            int rc = (int)vulkanOffscreenRenderBegin.invokeExact(
                    requireHandle(session, "vulkan session"),
                    requireHandle(image, "offscreen image"),
                    outCommandBuffer);
            if (rc == OFFSCREEN_RESULT_NO_VULKAN) {
                throw new IllegalStateException("nar_vulkan_offscreen_render_begin: no Vulkan loader/device");
            }
            if (rc < 0) {
                throw new IllegalStateException("nar_vulkan_offscreen_render_begin failed (native status " + rc + ")");
            }
            return outCommandBuffer.get(ValueLayout.ADDRESS, 0);
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public int vulkanOffscreenRenderEnd(MemorySegment session, MemorySegment image) {
        requireOffscreenAbi("vulkanOffscreenRenderEnd");
        try {
            return (int)vulkanOffscreenRenderEnd.invokeExact(
                    requireHandle(session, "vulkan session"),
                    requireHandle(image, "offscreen image"));
        } catch (Throwable t) { throw rethrow(t); }
    }

    @Override public int vulkanOffscreenReadback(MemorySegment session, MemorySegment image,
                                                 MemorySegment dst, long capacity) {
        requireOffscreenAbi("vulkanOffscreenReadback");
        if (capacity < 0) throw new IllegalArgumentException("capacity must be >= 0");
        requireRange(dst, capacity, "offscreen readback dst");
        try {
            return (int)vulkanOffscreenReadback.invokeExact(
                    requireHandle(session, "vulkan session"),
                    requireHandle(image, "offscreen image"),
                    dst, capacity);
        } catch (Throwable t) { throw rethrow(t); }
    }

    private void requireOffscreenAbi(String method) {
        if (!vulkanOffscreenSupported) throw new UnsupportedOperationException(
                method + ": Vulkan offscreen ABI not present in this library build");
    }

    // Optional Vulkan buffer resource ABI overrides.

    @Override public boolean vulkanBufferSupported() { return vulkanBufferSupported; }

    @Override public MemorySegment vulkanBufferCreate(MemorySegment session, long size) {
        requireBufferAbi("vulkanBufferCreate");
        if (size <= 0) throw new IllegalArgumentException("buffer size must be positive");
        try {
            MemorySegment handle = (MemorySegment) vulkanBufferCreate.invokeExact(
                    requireHandle(session, "vulkan session"), size);
            if (handle == null || handle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("nar_vulkan_buffer_create returned null — Vulkan unavailable or OOM");
            }
            return handle;
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void vulkanBufferDestroy(MemorySegment session, MemorySegment buffer) {
        requireBufferAbi("vulkanBufferDestroy");
        // Safe to call with a null buffer — mirrors the native contract.
        MemorySegment bufArg = (buffer == null) ? MemorySegment.NULL : buffer;
        try {
            vulkanBufferDestroy.invokeExact(requireHandle(session, "vulkan session"), bufArg);
        } catch (Throwable t) { throw rethrow(t); }
    }

    @Override public int vulkanBufferUpload(MemorySegment session, MemorySegment buffer,
                                            MemorySegment data, long size) {
        requireBufferAbi("vulkanBufferUpload");
        if (size < 0) throw new IllegalArgumentException("upload size must be >= 0");
        requireRange(data, size, "buffer upload data");
        try {
            return (int) vulkanBufferUpload.invokeExact(
                    requireHandle(session, "vulkan session"),
                    requireHandle(buffer, "vulkan buffer"),
                    data, size);
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public long vulkanBufferSize(MemorySegment buffer) {
        requireBufferAbi("vulkanBufferSize");
        MemorySegment bufArg = (buffer == null) ? MemorySegment.NULL : buffer;
        try {
            return (long) vulkanBufferSize.invokeExact(bufArg);
        } catch (Throwable t) { throw rethrow(t); }
    }

    private void requireBufferAbi(String method) {
        if (!vulkanBufferSupported) throw new UnsupportedOperationException(
                method + ": Vulkan buffer ABI not present in this library build");
    }

    // Optional shader module + graphics pipeline + command recording ABI overrides.

    @Override public boolean vulkanPipelineSupported() { return vulkanPipelineSupported; }

    @Override public MemorySegment vulkanShaderModuleCreate(MemorySegment session, MemorySegment code, long codeSize) {
        requirePipelineAbi("vulkanShaderModuleCreate");
        if (codeSize <= 0 || codeSize % 4 != 0)
            throw new IllegalArgumentException("SPIR-V code_size must be a positive multiple of 4");
        requireRange(code, codeSize, "SPIR-V code");
        try {
            MemorySegment handle = (MemorySegment) vulkanShaderModuleCreate.invokeExact(
                    requireHandle(session, "vulkan session"), code, codeSize);
            if (handle == null || handle.equals(MemorySegment.NULL))
                throw new IllegalStateException("nar_vulkan_shader_module_create returned null");
            return handle;
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void vulkanShaderModuleDestroy(MemorySegment session, MemorySegment module) {
        requirePipelineAbi("vulkanShaderModuleDestroy");
        MemorySegment modArg = (module == null) ? MemorySegment.NULL : module;
        try {
            vulkanShaderModuleDestroy.invokeExact(requireHandle(session, "vulkan session"), modArg);
        } catch (Throwable t) { throw rethrow(t); }
    }

    @Override public MemorySegment vulkanPipelineCreate(MemorySegment session, MemorySegment image,
                                                        MemorySegment vertexShader,
                                                        MemorySegment fragmentShader) {
        requirePipelineAbi("vulkanPipelineCreate");
        try {
            MemorySegment handle = (MemorySegment) vulkanPipelineCreate.invokeExact(
                    requireHandle(session, "vulkan session"),
                    requireHandle(image, "offscreen image"),
                    requireHandle(vertexShader, "vertex shader module"),
                    requireHandle(fragmentShader, "fragment shader module"));
            if (handle == null || handle.equals(MemorySegment.NULL))
                throw new IllegalStateException("nar_vulkan_pipeline_create returned null");
            return handle;
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public void vulkanPipelineDestroy(MemorySegment session, MemorySegment pipeline) {
        requirePipelineAbi("vulkanPipelineDestroy");
        MemorySegment plArg = (pipeline == null) ? MemorySegment.NULL : pipeline;
        try {
            vulkanPipelineDestroy.invokeExact(requireHandle(session, "vulkan session"), plArg);
        } catch (Throwable t) { throw rethrow(t); }
    }

    @Override public int vulkanCmdBindPipeline(MemorySegment session, MemorySegment commandBuffer,
                                               MemorySegment pipeline) {
        requirePipelineAbi("vulkanCmdBindPipeline");
        try {
            return (int) vulkanCmdBindPipeline.invokeExact(
                    requireHandle(session, "vulkan session"),
                    requireHandle(commandBuffer, "command buffer"),
                    requireHandle(pipeline, "pipeline"));
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public int vulkanCmdBindVertexBuffer(MemorySegment session, MemorySegment commandBuffer,
                                                   MemorySegment buffer, long offset) {
        requirePipelineAbi("vulkanCmdBindVertexBuffer");
        if (offset < 0) throw new IllegalArgumentException("vertex buffer offset must be >= 0");
        try {
            return (int) vulkanCmdBindVertexBuffer.invokeExact(
                    requireHandle(session, "vulkan session"),
                    requireHandle(commandBuffer, "command buffer"),
                    requireHandle(buffer, "vertex buffer"),
                    offset);
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    @Override public int vulkanCmdDraw(MemorySegment session, MemorySegment commandBuffer,
                                       int vertexCount, int firstVertex) {
        requirePipelineAbi("vulkanCmdDraw");
        if (vertexCount <= 0) throw new IllegalArgumentException("vertexCount must be positive");
        if (firstVertex < 0) throw new IllegalArgumentException("firstVertex must be >= 0");
        try {
            return (int) vulkanCmdDraw.invokeExact(
                    requireHandle(session, "vulkan session"),
                    requireHandle(commandBuffer, "command buffer"),
                    vertexCount, firstVertex);
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }

    private void requirePipelineAbi(String method) {
        if (!vulkanPipelineSupported) throw new UnsupportedOperationException(
                method + ": Vulkan pipeline ABI not present in this library build");
    }

    private void requireSessionAbi(String method) {
        if (!vulkanSessionSupported) throw new UnsupportedOperationException(
                method + ": Vulkan session ABI not present in this library build");
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
    /** Bind an optional symbol; returns null when the symbol is absent so older ABI-2 libraries still load. */
    private static MethodHandle optDowncall(Linker linker, SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        return lookup.find(symbol)
                .map(address -> linker.downcallHandle(address, descriptor))
                .orElse(null);
    }
    private static void invokeStatus(MethodHandle handle, String operation, Object... args) {
        try {
            int rc = (int)handle.invokeWithArguments(args);
            if (rc != 0) throw new IllegalArgumentException(operation + " rejected input (native status " + rc + ")");
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw rethrow(t); }
    }
    /** Allocate a NUL-terminated UTF-8 native string without depending on newer Arena helpers. */
    private static MemorySegment allocateUtf8(Arena arena, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        MemorySegment segment = arena.allocate(bytes.length + 1L, 1L);
        if (bytes.length > 0) {
            MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0L, bytes.length);
        }
        segment.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
        return segment;
    }
    private static void invokeVoidAddress(MethodHandle handle, MemorySegment arg) {
        try { handle.invokeExact(arg); } catch (Throwable t) { throw rethrow(t); }
    }

    private static void invokeVoid(MethodHandle handle, int arg) {
        try { handle.invokeExact(arg); } catch (Throwable t) { throw rethrow(t); }
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

