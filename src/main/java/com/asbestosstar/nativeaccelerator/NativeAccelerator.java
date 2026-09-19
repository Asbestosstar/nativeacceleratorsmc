package com.asbestosstar.nativeaccelerator;

import com.asbestosstar.nativeaccelerator.cache.PersistentResourceCache;
import com.asbestosstar.nativeaccelerator.nativeapi.Capabilities;
import com.asbestosstar.nativeaccelerator.nativeapi.NativeApi;
import com.asbestosstar.nativeaccelerator.nativeapi.PanamaNativeApi;
import com.asbestosstar.nativeaccelerator.platform.Platform;
import com.asbestosstar.nativeaccelerator.platform.ProcessInitializationGuard;
import com.asbestosstar.nativeaccelerator.renderer.NativeVulkanRenderer;
import com.asbestosstar.nativeaccelerator.renderer.RendererPlatformPolicy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeAccelerator {
    public static final String MOD_ID = "nativeaccelerator";

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static volatile NativeApi api;

    private NativeAccelerator() {}

    /**
     * Idempotent mod initialization.
     *
     * <p>Native Accelerator ships one JAR that several loaders may all discover. The local
     * {@link AtomicBoolean} only protects against a second call in the same classloader, so a host with
     * two or more loaders installed could otherwise initialise the mod twice. The claim taken here lives
     * in the JVM system property table, which every classloader shares, so whichever loader arrives first
     * wins and any later loader entrypoint returns without duplicating the native load, the renderer probe
     * or the hooks.</p>
     */
    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) return;

        // Cross-loader guard: a copy of this class loaded by another classloader has its own INITIALIZED
        // flag, but the shared system properties let it see that initialization already ran in this JVM.
        if (!ProcessInitializationGuard.claim(ProcessInitializationGuard.MOD_INITIALIZATION)) {
            System.out.println("[Native Accelerator] Mod is already initialized in this JVM ("
                    + ProcessInitializationGuard.claimDescription(ProcessInitializationGuard.MOD_INITIALIZATION)
                    + "); this loader entrypoint stands down to avoid a duplicate mod instance.");
            return;
        }

        Platform platform = Platform.current();
        System.out.println("[Native Accelerator] Platform: " + platform.id());

        try {
            PanamaNativeApi loaded = PanamaNativeApi.loadBundled();
            api = loaded;
            long caps = loaded.capabilities();
            System.out.println("[Native Accelerator] Panama backend: " + loaded.backendName());
            System.out.println("[Native Accelerator] Capabilities: " + Capabilities.names(caps));
        } catch (Throwable t) {
            api = null;
            System.err.println("[Native Accelerator] Native backend unavailable; Java path remains active: "
                    + t.getMessage());
        }

        // Keep the renderer client-only without binding the common core to any loader API.
        // Class lookup is non-initializing; dedicated-server jars normally do not contain this class.
        if (minecraftClientClassPresent()) {
            // The loader already knows where Minecraft keeps options.txt; adopt that real location so the
            // backend evidence is read from the actual game directory instead of a guessed path.
            RendererPlatformPolicy.adoptLoaderGameDirectory();
            PersistentResourceCache.initialize();
            NativeVulkanRenderer.initializeIfEnabled();
        }
    }

    private static boolean minecraftClientClassPresent() {
        try {
            Class.forName("net.minecraft.client.Minecraft", false, NativeAccelerator.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    public static Optional<NativeApi> api() {
        return Optional.ofNullable(api);
    }

    /** Small smoke test for the Java-to-native ABI. Not a benchmark. */
    public static boolean selfTest() {
        NativeApi nativeApi = api;
        if (nativeApi == null) return false;

        try (Arena arena = Arena.ofConfined()) {
            long size = 4096;
            MemorySegment a = arena.allocate(size, 64);
            MemorySegment b = arena.allocate(size, 64);
            MemorySegment out = arena.allocate(size, 64);

            for (long i = 0; i < size; i++) {
                a.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte) i);
                b.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte) (i * 31));
            }

            nativeApi.xorBytes(out, a, b, size);
            for (long i = 0; i < size; i++) {
                byte expected = (byte) (((byte) i) ^ ((byte) (i * 31)));
                if (out.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i) != expected) return false;
            }

            // Verify the runtime SimpleBitStorage layout with a non-divisor bit width.
            int bits = 5;
            int count = 257;
            int valuesPerLong = 64 / bits;
            MemorySegment values = arena.allocate((long) count * Integer.BYTES, 8);
            MemorySegment packed = arena.allocate((long) ((count + valuesPerLong - 1) / valuesPerLong) * Long.BYTES, 8);
            MemorySegment unpacked = arena.allocate((long) count * Integer.BYTES, 8);
            for (int i = 0; i < count; i++) {
                values.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i, (i * 7) & 31);
            }
            nativeApi.simpleBitsPack(packed, values, bits, count);
            nativeApi.simpleBitsUnpack(unpacked, packed, bits, count);
            for (int i = 0; i < count; i++) {
                if (unpacked.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i) != ((i * 7) & 31)) return false;
            }

            // Optional Solaris/SPARC libdax extension: verify signed Java range semantics,
            // including the unsigned wrap needed for a range crossing zero.
            if (nativeApi.daxIntScanAvailable()
                    && (nativeApi.capabilities() & Capabilities.DAX_DEVICE) != 0) {
                int[] probe = {-100, -10, -9, -1, 0, 1, 9, 10, 11, 100};
                MemorySegment daxSrc = arena.allocate((long) probe.length * Integer.BYTES, 64);
                MemorySegment daxDst = arena.allocate((long) probe.length * Integer.BYTES, 64);
                daxSrc.copyFrom(MemorySegment.ofArray(probe));
                if (nativeApi.daxCountI32Range(daxSrc, probe.length, -10, 10) != 7L) return false;
                long selected = nativeApi.daxSelectI32Range(daxDst, probe.length, daxSrc, probe.length, -10, 10);
                if (selected != 7L) return false;
                int[] expected = {-10, -9, -1, 0, 1, 9, 10};
                for (int i = 0; i < expected.length; i++) {
                    if (daxDst.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i) != expected[i]) return false;
                }
            }

            return true;
        }
    }
}
