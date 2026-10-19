package com.asbestosstar.nativeaccelerator.renderer.universal;

import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdBackendOwnership;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdBackendRegistry;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdBackendSelection;
import com.asbestosstar.nativeaccelerator.renderer.universal.urd.UrdValidationException;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure logic tests for the native Vulkan registration path in {@link UrdBackendRegistry}.
 *
 * <p>No native library, Minecraft class, or RenderPearl class is required. Each test clears
 * the global registry before use so tests do not interfere with each other.</p>
 */
public final class UrdBackendRegistryNativeVulkanTest {
    public static void main(String[] args) {
        testNullApiBlocked();
        testSessionAbsentBlocked();
        testNoLoaderBlocked();
        testNoDevicesBlocked();
        testSuccessfulRegistrationIsActive();
        testDuplicateRegistrationReturnsDuplicate();
        testClearAllowsReRegistration();
        testNativeVulkanBlocksSubsequentNativeVulkan();
        System.out.println("UrdBackendRegistryNativeVulkanTest: PASS");
    }

    // null api must return BLOCKED immediately.
    private static void testNullApiBlocked() {
        UrdBackendRegistry reg = freshRegistry();
        UrdBackendSelection sel = reg.registerNativeVulkan(null);
        require(sel.status() == UrdBackendSelection.Status.BLOCKED, "null api must be BLOCKED");
        require(sel.device() == null, "blocked selection must have null device");
    }

    // A stub that reports session ABI absent must be blocked.
    private static void testSessionAbsentBlocked() {
        UrdBackendRegistry reg = freshRegistry();
        UrdBackendSelection sel = reg.registerNativeVulkan(new StubApi(false, true, 1));
        require(sel.status() == UrdBackendSelection.Status.BLOCKED, "absent session ABI must be BLOCKED");
        require(sel.reason().contains("session ABI"), "reason should mention session ABI: " + sel.reason());
    }

    // A stub with no Vulkan loader must be blocked.
    private static void testNoLoaderBlocked() {
        UrdBackendRegistry reg = freshRegistry();
        UrdBackendSelection sel = reg.registerNativeVulkan(new StubApi(true, false, 1));
        require(sel.status() == UrdBackendSelection.Status.BLOCKED, "no loader must be BLOCKED");
        require(sel.reason().contains("loader") || sel.reason().contains("device"),
                "reason should mention loader/device: " + sel.reason());
    }

    // A stub with loader present but zero devices must be blocked.
    private static void testNoDevicesBlocked() {
        UrdBackendRegistry reg = freshRegistry();
        UrdBackendSelection sel = reg.registerNativeVulkan(new StubApi(true, true, 0));
        require(sel.status() == UrdBackendSelection.Status.BLOCKED, "zero devices must be BLOCKED");
    }

    // A fully capable stub must yield ACTIVE with NATIVE_VULKAN ownership.
    private static void testSuccessfulRegistrationIsActive() {
        UrdBackendRegistry reg = freshRegistry();
        StubApi api = new StubApi(true, true, 1);
        UrdBackendSelection sel = reg.registerNativeVulkan(api);
        require(sel.active(), "successful registration must be ACTIVE");
        require(sel.status() == UrdBackendSelection.Status.ACTIVE, "status must be ACTIVE");
        require(sel.ownership() == UrdBackendOwnership.NATIVE_VULKAN,
                "ownership must be NATIVE_VULKAN, got: " + sel.ownership());
        require(sel.device() != null, "active selection must have a non-null device");
        require(api.sessionCreated.get(), "Vulkan session must have been created");
        require(sel.adapter() != null, "adapter must be non-null");
        require(sel.adapter().name().startsWith("native-vulkan"),
                "adapter name should start with native-vulkan, got: " + sel.adapter().name());
    }

    // Calling registerNativeVulkan a second time with the same api returns DUPLICATE.
    private static void testDuplicateRegistrationReturnsDuplicate() {
        UrdBackendRegistry reg = freshRegistry();
        StubApi api = new StubApi(true, true, 1);
        UrdBackendSelection first = reg.registerNativeVulkan(api);
        require(first.active(), "first registration must be active");

        UrdBackendSelection second = reg.registerNativeVulkan(api);
        require(second.status() == UrdBackendSelection.Status.DUPLICATE,
                "second registration must be DUPLICATE");
        require(second.active(), "DUPLICATE must still count as active");
    }

    // After clear(), a fresh registerNativeVulkan should succeed.
    private static void testClearAllowsReRegistration() {
        UrdBackendRegistry reg = freshRegistry();
        StubApi api = new StubApi(true, true, 1);
        UrdBackendSelection first = reg.registerNativeVulkan(api);
        require(first.active(), "first registration must be active");

        reg.clear();
        require(!reg.selection().active(), "selection must be inactive after clear");

        StubApi api2 = new StubApi(true, true, 2);
        UrdBackendSelection second = reg.registerNativeVulkan(api2);
        require(second.status() == UrdBackendSelection.Status.ACTIVE,
                "re-registration after clear must be ACTIVE");
        require(api2.sessionCreated.get(), "new session must be created on re-registration");
    }

    // A second call while native Vulkan is active returns DUPLICATE, not a new session.
    private static void testNativeVulkanBlocksSubsequentNativeVulkan() {
        UrdBackendRegistry reg = freshRegistry();
        StubApi api = new StubApi(true, true, 1);
        reg.registerNativeVulkan(api);

        StubApi api2 = new StubApi(true, true, 1);
        UrdBackendSelection second = reg.registerNativeVulkan(api2);
        require(second.status() == UrdBackendSelection.Status.DUPLICATE,
                "second native Vulkan registration must be DUPLICATE, not a new session");
        require(!api2.sessionCreated.get(),
                "no new session should be created when DUPLICATE is returned");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * Returns a freshly cleared global registry. Because {@link UrdBackendRegistry#global()} is a
     * singleton, clearing before each test is the only safe way to run tests in sequence.
     */
    private static UrdBackendRegistry freshRegistry() {
        UrdBackendRegistry reg = UrdBackendRegistry.global();
        reg.clear();
        return reg;
    }

    /** Minimal stub covering only what {@code registerNativeVulkan} exercises. */
    private static final class StubApi implements RendererNativeApi {
        private static final MemorySegment FAKE_SESSION = MemorySegment.ofAddress(0xABCD1234L);

        final AtomicBoolean sessionCreated = new AtomicBoolean();

        private final boolean sessionSupported;
        private final boolean loaderAvailable;
        private final int deviceCount;

        StubApi(boolean sessionSupported, boolean loaderAvailable, int deviceCount) {
            this.sessionSupported = sessionSupported;
            this.loaderAvailable = loaderAvailable;
            this.deviceCount = deviceCount;
        }

        @Override public boolean vulkanSessionSupported() { return sessionSupported; }
        @Override public boolean vulkanLoaderAvailable() { return loaderAvailable; }
        @Override public int vulkanPhysicalDeviceCount() { return deviceCount; }

        @Override public MemorySegment vulkanSessionCreate(int mode, int width, int height) {
            sessionCreated.set(true);
            return FAKE_SESSION;
        }
        @Override public void vulkanSessionDestroy(MemorySegment session) {}
        @Override public int vulkanSessionTier(MemorySegment session) {
            return RendererNativeApi.VULKAN_TIER_BASIC;
        }
        @Override public long vulkanSessionFeatures(MemorySegment session) { return 0L; }
        @Override public String vulkanSessionLastError(MemorySegment session) { return "stub"; }

        // Remaining required methods — none called by registry registration.
        @Override public int abiVersion() { return ABI_VERSION; }
        @Override public long capabilities() { return 0; }
        @Override public String backendName() { return "stub"; }
        @Override public int vulkanLoaderApiVersion() { return 0; }
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
