package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure logic tests for NativeVulkanUrdBackend, NativeVulkanUrdDevice, NativeVulkanUrdTexture
 * and VulkanCapabilityTier. No native library is required. A stub RendererNativeApi simulates
 * session/offscreen ABI availability.
 */
public final class NativeVulkanUrdBackendTest {
    public static void main(String[] args) {
        testUnavailableTierThrowsOnCreateDevice();
        testSessionAbiAbsentThrowsOnCreateDevice();
        testSessionCreateCalledAndDeviceReturned();
        testSessionDestroyCalledOnDeviceClose();
        testDeviceCapabilitiesFromNativeTierAndFeatures();
        testDeviceClosedGuardOnCreateBuffer();
        // New offscreen-path coverage.
        testCreateTextureRequiresOffscreenAbi();
        testCreateTextureValidatesDimensions();
        testCreateTextureDelegatesToOffscreenImageCreate();
        testTextureReadbackInvokesNativeReadback();
        testTextureReadbackRejectsNullTarget();
        testDeviceReadbackRejectsForeignTexture();
        testTextureCloseDestroysOffscreenImage();
        testClosedTextureGuards();
        // Buffer ABI coverage.
        testCreateBufferRequiresBufferAbi();
        testCreateBufferValidatesSize();
        testCreateBufferDelegatesToNative();
        testBufferUploadRejectsNonZeroOffset();
        testBufferUploadRejectsOverflow();
        testBufferUploadDelegatesToNative();
        testBufferCloseDestroysNativeBuffer();
        testClosedBufferGuards();
        testCreateBufferOnClosedDevice();
        System.out.println("NativeVulkanUrdBackendTest: PASS");
    }

    // UNAVAILABLE tier must throw before even touching the API.
    private static void testUnavailableTierThrowsOnCreateDevice() {
        var backend = new NativeVulkanUrdBackend(null, VulkanCapabilityTier.UNAVAILABLE, "no loader");
        try {
            backend.createDevice();
            throw new AssertionError("expected UrdValidationException for UNAVAILABLE tier");
        } catch (UrdValidationException ok) {
            require(ok.getMessage().contains("unavailable"), "message should mention unavailable");
        }
    }

    // BASIC tier with a null api (no session ABI) must throw a clear message.
    private static void testSessionAbiAbsentThrowsOnCreateDevice() {
        var backend = new NativeVulkanUrdBackend(null, VulkanCapabilityTier.BASIC, "null api");
        try {
            backend.createDevice();
            throw new AssertionError("expected UrdValidationException for null api");
        } catch (UrdValidationException ok) {
            require(ok.getMessage().contains("no renderer API"), "message should mention api");
        }
    }

    // A stub that reports session ABI supported should yield a NativeVulkanUrdDevice.
    private static void testSessionCreateCalledAndDeviceReturned() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC,
                RendererNativeApi.VULKAN_FEATURE_CLASSIC_RENDER_PASS | RendererNativeApi.VULKAN_FEATURE_CPU_CULL);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            require(stub.createCalled.get(), "session create must be called");
            require(!device.isClosed(), "device must start open");
            require(device.capabilities().supports("classic-render-pass"), "classic-render-pass feature");
            require(device.capabilities().supports("cpu-cull"), "cpu-cull feature");
        }
    }

    // Closing the device must call vulkanSessionDestroy exactly once.
    private static void testSessionDestroyCalledOnDeviceClose() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        UrdDevice device = backend.createDevice();
        require(!stub.destroyCalled.get(), "destroy must not be called before close");
        device.close();
        require(stub.destroyCalled.get(), "destroy must be called on close");
        device.close(); // second close must be a no-op
        require(device.isClosed(), "device must be closed");
    }

    // Feature bits from the native side should map to the right capability tokens.
    private static void testDeviceCapabilitiesFromNativeTierAndFeatures() {
        long gpuCullFeatures = RendererNativeApi.VULKAN_FEATURE_CLASSIC_RENDER_PASS
                | RendererNativeApi.VULKAN_FEATURE_CPU_CULL
                | RendererNativeApi.VULKAN_FEATURE_GPU_CULL;
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, gpuCullFeatures);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            UrdCapabilities caps = device.capabilities();
            require(caps.supports("vertex-fragment"), "vertex-fragment");
            require(caps.supports("rgba8-render-target"), "rgba8-render-target");
            require(caps.supports("offscreen"), "offscreen");
            require(caps.supports("classic-render-pass"), "classic-render-pass");
            require(caps.supports("cpu-cull"), "cpu-cull");
            require(caps.supports("gpu-cull"), "gpu-cull");
            require(caps.supports("draw-indirect"), "draw-indirect from gpu-cull");
        }
    }

    // Resource creation on a closed device must throw.
    private static void testDeviceClosedGuardOnCreateBuffer() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, false);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        UrdDevice device = backend.createDevice();
        device.close();
        try {
            device.createBuffer(64);
            throw new AssertionError("expected UrdValidationException on closed device");
        } catch (UrdValidationException ok) { /* expected */ }
    }

    // createTexture must throw when offscreen ABI is absent.
    private static void testCreateTextureRequiresOffscreenAbi() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, false);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            try {
                device.createTexture(64, 64);
                throw new AssertionError("expected UrdValidationException for absent offscreen ABI");
            } catch (UrdValidationException ok) {
                require(ok.getMessage().contains("offscreen ABI"), "message should mention offscreen ABI");
            }
        }
    }

    // createTexture must throw for non-positive dimensions.
    private static void testCreateTextureValidatesDimensions() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            try {
                device.createTexture(0, 64);
                throw new AssertionError("expected UrdValidationException for zero width");
            } catch (UrdValidationException ok) {
                require(ok.getMessage().contains("dimensions"), "message should mention dimensions");
            }
        }
    }

    // createTexture must call imageCreate and return a live NativeVulkanUrdTexture.
    private static void testCreateTextureDelegatesToOffscreenImageCreate() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            require(stub.imageCreateCalled.get() == 0, "imageCreate not yet called");
            try (UrdTexture tex = device.createTexture(16, 16)) {
                require(stub.imageCreateCalled.get() == 1, "imageCreate called once");
                require(!tex.isClosed(), "texture must start open");
                require(tex.width() == 16, "width");
                require(tex.height() == 16, "height");
            }
        }
    }

    // readback on a live texture must invoke the native readback and deliver pixels.
    private static void testTextureReadbackInvokesNativeReadback() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            try (UrdTexture tex = device.createTexture(4, 4)) {
                final java.nio.ByteBuffer[] received = {null};
                tex.readback(pixels -> received[0] = pixels);
                require(stub.readbackCalled.get(), "readback must be called");
                require(received[0] != null, "pixel buffer must be delivered");
                require(received[0].remaining() == 4 * 4 * 4, "pixel buffer size 4*4*4");
            }
        }
    }

    // readback with a null target must throw.
    private static void testTextureReadbackRejectsNullTarget() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            try (UrdTexture tex = device.createTexture(4, 4)) {
                try {
                    tex.readback(null);
                    throw new AssertionError("expected IllegalArgumentException for null target");
                } catch (IllegalArgumentException ok) { /* expected */ }
            }
        }
    }

    // device.readback with a texture from a different type must throw.
    private static void testDeviceReadbackRejectsForeignTexture() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            // A stub texture that is not a NativeVulkanUrdTexture.
            UrdTexture foreign = new UrdTexture() {
                @Override public int width() { return 1; }
                @Override public int height() { return 1; }
                @Override public void readback(CommandReadbackTarget t) {}
                @Override public boolean isClosed() { return false; }
                @Override public void close() {}
            };
            try {
                device.readback(foreign);
                throw new AssertionError("expected UrdValidationException for foreign texture");
            } catch (UrdValidationException ok) { /* expected */ }
        }
    }

    // closing a texture must call imageDestroy exactly once.
    private static void testTextureCloseDestroysOffscreenImage() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            UrdTexture tex = device.createTexture(8, 8);
            require(stub.imageDestroyCalled.get() == 0, "destroy not called before close");
            tex.close();
            require(stub.imageDestroyCalled.get() == 1, "destroy called once on close");
            tex.close(); // second close is a no-op
            require(stub.imageDestroyCalled.get() == 1, "destroy not called again on double-close");
            require(tex.isClosed(), "texture must be closed");
        }
    }

    // accessing width/height/readback on a closed texture must throw.
    private static void testClosedTextureGuards() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            UrdTexture tex = device.createTexture(8, 8);
            tex.close();
            try { tex.width(); throw new AssertionError("expected exception on closed tex.width()"); }
            catch (UrdValidationException ok) { /* expected */ }
            try { tex.height(); throw new AssertionError("expected exception on closed tex.height()"); }
            catch (UrdValidationException ok) { /* expected */ }
            try { tex.readback(p -> {}); throw new AssertionError("expected exception on closed tex.readback()"); }
            catch (UrdValidationException ok) { /* expected */ }
        }
    }

    // createBuffer must throw when the buffer ABI is absent.
    private static void testCreateBufferRequiresBufferAbi() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, true, false);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            try {
                device.createBuffer(64);
                throw new AssertionError("expected UrdValidationException for absent buffer ABI");
            } catch (UrdValidationException ok) {
                require(ok.getMessage().contains("buffer ABI"), "message should mention buffer ABI");
            }
        }
    }

    // createBuffer must throw for non-positive size.
    private static void testCreateBufferValidatesSize() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, false, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            try {
                device.createBuffer(0);
                throw new AssertionError("expected UrdValidationException for zero size");
            } catch (UrdValidationException ok) {
                require(ok.getMessage().contains("size"), "message should mention size");
            }
        }
    }

    // createBuffer must call vulkanBufferCreate and return a live NativeVulkanUrdBuffer with correct size.
    private static void testCreateBufferDelegatesToNative() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, false, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            require(stub.bufferCreateCalled.get() == 0, "bufferCreate not yet called");
            try (UrdBuffer buf = device.createBuffer(128)) {
                require(stub.bufferCreateCalled.get() == 1, "bufferCreate called once");
                require(stub.lastBufferSize.get() == 128L, "size passed to native");
                require(!buf.isClosed(), "buffer must start open");
                require(buf.size() == 128L, "buffer size must match");
            }
        }
    }

    // upload with a non-zero offset must throw.
    private static void testBufferUploadRejectsNonZeroOffset() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, false, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            try (UrdBuffer buf = device.createBuffer(128)) {
                java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(16);
                try {
                    buf.upload(1, data);
                    throw new AssertionError("expected UrdValidationException for non-zero offset");
                } catch (UrdValidationException ok) {
                    require(ok.getMessage().contains("offset"), "message should mention offset");
                }
                require(stub.bufferUploadCalled.get() == 0, "upload must not reach native");
            }
        }
    }

    // upload that exceeds capacity must throw.
    private static void testBufferUploadRejectsOverflow() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, false, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            try (UrdBuffer buf = device.createBuffer(64)) {
                java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(128);
                try {
                    buf.upload(0, data);
                    throw new AssertionError("expected UrdValidationException for overflow");
                } catch (UrdValidationException ok) {
                    require(ok.getMessage().contains("exceeds"), "message should mention exceeds");
                }
                require(stub.bufferUploadCalled.get() == 0, "upload must not reach native");
            }
        }
    }

    // upload with a valid range must delegate to vulkanBufferUpload.
    private static void testBufferUploadDelegatesToNative() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, false, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            try (UrdBuffer buf = device.createBuffer(256)) {
                java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(32);
                for (int i = 0; i < 32; i++) data.put(i, (byte) i);
                buf.upload(0, data);
                require(stub.bufferUploadCalled.get() == 1, "upload must be called");
                require(stub.lastUploadSize.get() == 32L, "upload size passed to native");
            }
        }
    }

    // closing a buffer must call vulkanBufferDestroy exactly once.
    private static void testBufferCloseDestroysNativeBuffer() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, false, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            UrdBuffer buf = device.createBuffer(64);
            require(stub.bufferDestroyCalled.get() == 0, "destroy not called before close");
            buf.close();
            require(stub.bufferDestroyCalled.get() == 1, "destroy called once on close");
            buf.close(); // second close is a no-op
            require(stub.bufferDestroyCalled.get() == 1, "destroy not called again on double-close");
            require(buf.isClosed(), "buffer must be closed");
        }
    }

    // accessing size/upload on a closed buffer must throw.
    private static void testClosedBufferGuards() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, false, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        try (UrdDevice device = backend.createDevice()) {
            UrdBuffer buf = device.createBuffer(64);
            buf.close();
            try { buf.size(); throw new AssertionError("expected exception on closed buf.size()"); }
            catch (UrdValidationException ok) { /* expected */ }
            try { buf.upload(0, java.nio.ByteBuffer.allocate(4));
                throw new AssertionError("expected exception on closed buf.upload()"); }
            catch (UrdValidationException ok) { /* expected */ }
        }
    }

    // createBuffer on a closed device must throw.
    private static void testCreateBufferOnClosedDevice() {
        var stub = new StubApi(true, RendererNativeApi.VULKAN_TIER_BASIC, 0L, false, true);
        var backend = new NativeVulkanUrdBackend(stub, VulkanCapabilityTier.BASIC, "");
        UrdDevice device = backend.createDevice();
        device.close();
        try {
            device.createBuffer(64);
            throw new AssertionError("expected UrdValidationException on closed device");
        } catch (UrdValidationException ok) { /* expected */ }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    // Minimal stub that implements only what NativeVulkanUrdDevice needs.
    private static final class StubApi implements RendererNativeApi {
        private static final MemorySegment FAKE_SESSION = MemorySegment.ofAddress(0xCAFEBABEL);
        private static final MemorySegment FAKE_IMAGE = MemorySegment.ofAddress(0xDEADBEEFL);
        private static final MemorySegment FAKE_BUFFER = MemorySegment.ofAddress(0xBEEFCAFEL);

        final AtomicBoolean createCalled  = new AtomicBoolean();
        final AtomicBoolean destroyCalled = new AtomicBoolean();
        final AtomicInteger imageCreateCalled = new AtomicInteger();
        final AtomicInteger imageDestroyCalled = new AtomicInteger();
        final AtomicBoolean readbackCalled = new AtomicBoolean();
        final AtomicLong lastReadbackCapacity = new AtomicLong(-1L);
        final AtomicInteger bufferCreateCalled = new AtomicInteger();
        final AtomicInteger bufferDestroyCalled = new AtomicInteger();
        final AtomicInteger bufferUploadCalled = new AtomicInteger();
        final AtomicLong lastBufferSize = new AtomicLong(-1L);
        final AtomicLong lastUploadSize = new AtomicLong(-1L);

        private final boolean sessionSupported;
        private final boolean offscreenSupported;
        private final boolean bufferSupported;
        private final int tier;
        private final long features;

        StubApi(boolean sessionSupported, int tier, long features) {
            this(sessionSupported, tier, features, false, false);
        }

        StubApi(boolean sessionSupported, int tier, long features, boolean offscreenSupported) {
            this(sessionSupported, tier, features, offscreenSupported, false);
        }

        StubApi(boolean sessionSupported, int tier, long features,
                boolean offscreenSupported, boolean bufferSupported) {
            this.sessionSupported = sessionSupported;
            this.offscreenSupported = offscreenSupported;
            this.bufferSupported = bufferSupported;
            this.tier = tier;
            this.features = features;
        }

        @Override public boolean vulkanSessionSupported() { return sessionSupported; }

        @Override public MemorySegment vulkanSessionCreate(int mode, int width, int height) {
            createCalled.set(true);
            return FAKE_SESSION;
        }
        @Override public void vulkanSessionDestroy(MemorySegment session) { destroyCalled.set(true); }
        @Override public int vulkanSessionTier(MemorySegment session) { return tier; }
        @Override public long vulkanSessionFeatures(MemorySegment session) { return features; }
        @Override public String vulkanSessionLastError(MemorySegment session) { return "stub"; }

        @Override public boolean vulkanOffscreenSupported() { return offscreenSupported; }
        @Override public MemorySegment vulkanOffscreenImageCreate(MemorySegment session, int width, int height) {
            imageCreateCalled.incrementAndGet();
            return FAKE_IMAGE;
        }
        @Override public void vulkanOffscreenImageDestroy(MemorySegment session, MemorySegment image) {
            imageDestroyCalled.incrementAndGet();
        }
        @Override public MemorySegment vulkanOffscreenRenderBegin(MemorySegment session, MemorySegment image) {
            return MemorySegment.NULL;
        }
        @Override public int vulkanOffscreenRenderEnd(MemorySegment session, MemorySegment image) {
            return OFFSCREEN_RESULT_OK;
        }
        @Override public int vulkanOffscreenReadback(MemorySegment session, MemorySegment image,
                                                     MemorySegment dst, long capacity) {
            readbackCalled.set(true);
            lastReadbackCapacity.set(capacity);
            // Fill the dst with a recognizable pattern so tests can check size.
            long bytes = Math.min(capacity, dst.byteSize());
            for (long i = 0; i < bytes; i++) {
                dst.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte) 0xAB);
            }
            return OFFSCREEN_RESULT_OK;
        }

        // Buffer ABI stub.
        @Override public boolean vulkanBufferSupported() { return bufferSupported; }
        @Override public MemorySegment vulkanBufferCreate(MemorySegment session, long size) {
            bufferCreateCalled.incrementAndGet();
            lastBufferSize.set(size);
            return FAKE_BUFFER;
        }
        @Override public void vulkanBufferDestroy(MemorySegment session, MemorySegment buffer) {
            bufferDestroyCalled.incrementAndGet();
        }
        @Override public int vulkanBufferUpload(MemorySegment session, MemorySegment buffer,
                                                MemorySegment data, long size) {
            bufferUploadCalled.incrementAndGet();
            lastUploadSize.set(size);
            return VULKAN_BUFFER_RESULT_OK;
        }
        @Override public long vulkanBufferSize(MemorySegment buffer) {
            // Reflect the most recently requested size so the device uses the native value.
            return lastBufferSize.get();
        }

        // All ABI-2 members must compile; none are called in these tests.
        @Override public int abiVersion() { return ABI_VERSION; }
        @Override public long capabilities() { return 0; }
        @Override public String backendName() { return "stub"; }
        @Override public boolean vulkanLoaderAvailable() { return true; }
        @Override public int vulkanLoaderApiVersion() { return 0; }
        @Override public int vulkanPhysicalDeviceCount() { return 1; }
        @Override public String vulkanLoaderName() { return "stub"; }
        @Override public int rendererPlatformRole() { return ROLE_AUTO; }
        @Override public boolean rendererClientEligible() { return true; }
        @Override public int rendererPlatformEvidence() { return 0; }
        @Override public String rendererPlatformName() { return "stub"; }
        @Override public void setRendererRoleOverride(int role) {}
        @Override public void setMinecraftOptionsPath(String path) {}
        @Override public String minecraftOptionsPath() { return ""; }
        @Override public MemorySegment createContext(int workers) { return MemorySegment.NULL; }
        @Override public void destroyContext(MemorySegment ctx) {}
        @Override public int contextWorkerCount(MemorySegment ctx) { return 0; }
        @Override public MemorySegment createArena(long cap, long align) { return MemorySegment.NULL; }
        @Override public void destroyArena(MemorySegment arena) {}
        @Override public long arenaAllocate(MemorySegment arena, long size, long align) { return 0; }
        @Override public void arenaFree(MemorySegment arena, long off, long size) {}
        @Override public MemorySegment createScene(int cap) { return MemorySegment.NULL; }
        @Override public void destroyScene(MemorySegment scene) {}
        @Override public int sceneCount(MemorySegment scene) { return 0; }
        @Override public void sceneUpsert(MemorySegment scene, long key,
                float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                int fi, int ic, int vo, int fit, int mm) {}
        @Override public boolean sceneRemove(MemorySegment scene, long key) { return false; }
        @Override public int sceneBuildIndirect(MemorySegment scene, MemorySegment planes,
                float cx, float cy, float cz, float md, int mm,
                MemorySegment cmds, MemorySegment keys, int cap) { return 0; }
        @Override public int sceneExportGpu(MemorySegment scene,
                MemorySegment min, MemorySegment max,
                MemorySegment meta, MemorySegment keys, int cap) { return 0; }
        @Override public void voxelFaceMasks(MemorySegment out,
                MemorySegment occ, MemorySegment nb) {}
        @Override public void voxelFaceMasksBatch(MemorySegment ctx,
                MemorySegment out, MemorySegment occ,
                MemorySegment nb, int sc) {}
    }
}
