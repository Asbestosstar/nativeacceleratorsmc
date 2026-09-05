package com.asbestosstar.nativeaccelerator.renderer;

import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native section database used to produce compact indirect-draw lists. */
public final class NativeScene implements AutoCloseable {
    private final RendererNativeApi api;
    private final MemorySegment handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    NativeScene(RendererNativeApi api, int initialCapacity) {
        this.api = api;
        this.handle = api.createScene(initialCapacity);
    }

    public int sectionCount() {
        ensureOpen();
        return api.sceneCount(handle);
    }

    public void upsert(long sectionKey,
                       float minX, float minY, float minZ,
                       float maxX, float maxY, float maxZ,
                       int firstIndex, int indexCount, int vertexOffset,
                       int firstInstance, int materialMask) {
        ensureOpen();
        api.sceneUpsert(handle, sectionKey,
                minX, minY, minZ, maxX, maxY, maxZ,
                firstIndex, indexCount, vertexOffset, firstInstance, materialMask);
    }

    public boolean remove(long sectionKey) {
        ensureOpen();
        return api.sceneRemove(handle, sectionKey);
    }

    /**
     * Build Vulkan-layout VkDrawIndexedIndirectCommand records into outCommands.
     * frustumPlanes can be null to disable frustum culling.
     */
    public int buildIndirect(MemorySegment frustumPlanes,
                             float cameraX, float cameraY, float cameraZ,
                             float maxDistanceSquared, int requiredMaterialMask,
                             MemorySegment outCommands, MemorySegment outSectionKeys,
                             int outputCapacity) {
        ensureOpen();
        return api.sceneBuildIndirect(handle, frustumPlanes,
                cameraX, cameraY, cameraZ, maxDistanceSquared, requiredMaterialMask,
                outCommands, outSectionKeys, outputCapacity);
    }

    /** Export compact SoA buffers suitable for the section-culling compute shader. */
    public int exportGpu(MemorySegment outBoundsMin, MemorySegment outBoundsMax,
                         MemorySegment outDrawMeta, MemorySegment outSectionKeys, int outputCapacity) {
        ensureOpen();
        return api.sceneExportGpu(handle, outBoundsMin, outBoundsMax, outDrawMeta, outSectionKeys, outputCapacity);
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Native scene is closed");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) api.destroyScene(handle);
    }
}
