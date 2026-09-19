package com.asbestosstar.nativeaccelerator.platform;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Best-effort current-LWP processor binding and pset discovery for Solaris/illumos using Java 25 FFM. */
public final class SolarisThreadAffinity {
    // sys/procset.h: P_LWPID is the ninth enum value (8), sys/types.h: P_MYID=-1.
    // sys/processor.h: PBIND_NONE=-1. sys/pset.h: PS_MYID=-3.
    private static final int P_LWPID = 8;
    private static final int P_MYID = -1;
    private static final int PBIND_NONE = -1;
    private static final int PS_MYID = -3;
    private static final MethodHandle PROCESSOR_BIND = find("processor_bind", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle PSET_INFO = find("pset_info", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private SolarisThreadAffinity() {}

    public static boolean available() { return PROCESSOR_BIND != null; }

    public static boolean bindCurrent(int cpuId) { return callBind(cpuId); }
    public static boolean unbindCurrent() { return callBind(PBIND_NONE); }

    /**
     * Processor ids visible to the caller's current processor set, or an empty array when unavailable.
     * Filtering the kstat topology through this list prevents a zone/pset-limited JVM from binding workers
     * to machine CPUs it cannot actually execute on.
     */
    public static int[] allowedProcessorIds() {
        if (PSET_INFO == null) return new int[0];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
            count.set(ValueLayout.JAVA_INT, 0L, 0);
            int rc = (int) PSET_INFO.invokeExact(PS_MYID, MemorySegment.NULL, count, MemorySegment.NULL);
            if (rc != 0) return new int[0];
            int n = count.get(ValueLayout.JAVA_INT, 0L);
            if (n <= 0 || n > 1_048_576) return new int[0];

            MemorySegment cpus = arena.allocate(ValueLayout.JAVA_INT, n);
            count.set(ValueLayout.JAVA_INT, 0L, n);
            rc = (int) PSET_INFO.invokeExact(PS_MYID, MemorySegment.NULL, count, cpus);
            if (rc != 0) return new int[0];
            int actual = Math.max(0, Math.min(n, count.get(ValueLayout.JAVA_INT, 0L)));
            int[] result = new int[actual];
            for (int i = 0; i < actual; ++i) {
                result[i] = cpus.getAtIndex(ValueLayout.JAVA_INT, i);
            }
            return result;
        } catch (Throwable ignored) {
            return new int[0];
        }
    }

    private static boolean callBind(int cpuId) {
        if (PROCESSOR_BIND == null) return false;
        try {
            int rc = (int) PROCESSOR_BIND.invokeExact(P_LWPID, P_MYID, cpuId, MemorySegment.NULL);
            return rc == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static MethodHandle find(String symbolName, FunctionDescriptor descriptor) {
        if (!"solaris".equals(Platform.current().os())) return null;
        try {
            Linker linker = Linker.nativeLinker();
            MemorySegment symbol = linker.defaultLookup().find(symbolName).orElse(null);
            return symbol == null ? null : linker.downcallHandle(symbol, descriptor);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
