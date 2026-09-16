package com.asbestosstar.nativeaccelerator.platform;

/** Best-effort native CPU cache information. Zero means the host did not expose that level reliably. */
public record CpuCacheInfo(long l1DataBytes, long l2Bytes, long l3Bytes, int lineBytes, String source) {
    public CpuCacheInfo {
        l1DataBytes = Math.max(0L, l1DataBytes);
        l2Bytes = Math.max(0L, l2Bytes);
        l3Bytes = Math.max(0L, l3Bytes);
        lineBytes = Math.max(0, lineBytes);
        source = source == null || source.isBlank() ? "unknown" : source;
    }
}
