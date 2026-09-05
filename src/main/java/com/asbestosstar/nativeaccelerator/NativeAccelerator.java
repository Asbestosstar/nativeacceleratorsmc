package com.asbestosstar.nativeaccelerator;

import com.asbestosstar.nativeaccelerator.nativeapi.Capabilities;
import com.asbestosstar.nativeaccelerator.nativeapi.NativeApi;
import com.asbestosstar.nativeaccelerator.nativeapi.PanamaNativeApi;
import com.asbestosstar.nativeaccelerator.platform.Platform;
import com.asbestosstar.nativeaccelerator.renderer.NativeVulkanRenderer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeAccelerator {
    public static final String MOD_ID = "nativeaccelerator";

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static volatile NativeApi api;

    private NativeAccelerator() {}

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) return;

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

            return true;
        }
    }
}
