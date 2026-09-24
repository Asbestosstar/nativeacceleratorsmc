package com.asbestosstar.nativeaccelerator.platform;

/** Dependency-free parser sanity checks for native cache discovery helpers. */
public final class CpuCacheDetectorTest {
    private CpuCacheDetectorTest() {}
    public static void main(String[] args) {
        check(CpuCacheDetector.parseSize("32K") == 32L * 1024L, "32K");
        check(CpuCacheDetector.parseSize("2M") == 2L * 1024L * 1024L, "2M");
        check(CpuCacheDetector.parseSize("65536") == 65536L, "bytes");
        check(CpuCacheDetector.parseSize("bad") == 0L, "invalid");
        CpuCacheInfo info = CpuCacheDetector.current();
        check(info != null, "current info");
        check(info.lineBytes() >= 0, "line size nonnegative");
        System.out.println("CpuCacheDetectorTest: PASS " + info);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

