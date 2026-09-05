package com.asbestosstar.nativeaccelerator.nativeapi;

import java.util.ArrayList;
import java.util.List;

public final class Capabilities {
    private Capabilities() {}

    public static final long NATIVE      = 1L << 0;
    public static final long SIMD        = 1L << 1;
    public static final long AVX2        = 1L << 2;
    public static final long AVX512F     = 1L << 3;
    public static final long SPARC_VIS   = 1L << 4;
    public static final long SPARC_VIS2  = 1L << 5;
    public static final long SPARC_VIS3  = 1L << 6;
    public static final long DAX_LIBRARY = 1L << 7;
    public static final long DAX_DEVICE     = 1L << 8;
    public static final long POSIX          = 1L << 9;
    public static final long WINDOWS        = 1L << 10;
    public static final long IA64           = 1L << 11;
    public static final long BIG_ENDIAN     = 1L << 12;
    public static final long LITTLE_ENDIAN  = 1L << 13;
    public static final long BIENDIAN_ARCH  = 1L << 14;
    public static final long PPC32          = 1L << 15;
    public static final long BSD            = 1L << 16;
    public static final long MACOS          = 1L << 17;
    public static final long FREEBSD        = 1L << 18;
    public static final long NETBSD         = 1L << 19;
    public static final long OPENBSD        = 1L << 20;
    public static final long LINUX          = 1L << 21;
    public static final long PACKED_BITS     = 1L << 22;
    public static final long QUAD_SORT       = 1L << 23;
    public static final long IMAGE_KERNELS   = 1L << 24;
    public static final long NOISE_KERNELS   = 1L << 25;

    public static List<String> names(long mask) {
        List<String> out = new ArrayList<>();
        add(out, mask, NATIVE, "native");
        add(out, mask, SIMD, "simd");
        add(out, mask, AVX2, "avx2");
        add(out, mask, AVX512F, "avx512f");
        add(out, mask, SPARC_VIS, "vis");
        add(out, mask, SPARC_VIS2, "vis2");
        add(out, mask, SPARC_VIS3, "vis3");
        add(out, mask, DAX_LIBRARY, "libdax");
        add(out, mask, DAX_DEVICE, "dax-device");
        add(out, mask, POSIX, "posix");
        add(out, mask, WINDOWS, "windows");
        add(out, mask, IA64, "ia64");
        add(out, mask, BIG_ENDIAN, "big-endian");
        add(out, mask, LITTLE_ENDIAN, "little-endian");
        add(out, mask, BIENDIAN_ARCH, "biendian-arch");
        add(out, mask, PPC32, "ppc32");
        add(out, mask, BSD, "bsd");
        add(out, mask, MACOS, "macos");
        add(out, mask, FREEBSD, "freebsd");
        add(out, mask, NETBSD, "netbsd");
        add(out, mask, OPENBSD, "openbsd");
        add(out, mask, LINUX, "linux");
        add(out, mask, PACKED_BITS, "packed-bits");
        add(out, mask, QUAD_SORT, "quad-sort");
        add(out, mask, IMAGE_KERNELS, "image-kernels");
        add(out, mask, NOISE_KERNELS, "noise-kernels");
        return List.copyOf(out);
    }

    private static void add(List<String> out, long mask, long bit, String name) {
        if ((mask & bit) != 0) out.add(name);
    }
}
