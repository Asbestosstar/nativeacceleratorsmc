package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

/** Resource limits applied to every shader-pack source operation. */
public record ShaderPackReadLimits(long maxFileBytes, long maxTotalBytes, int maxEntries, int maxIncludeDepth) {
    public static final ShaderPackReadLimits DEFAULT = new ShaderPackReadLimits(
            4L * 1024 * 1024, 64L * 1024 * 1024, 100_000, 64);

    public ShaderPackReadLimits {
        if (maxFileBytes <= 0 || maxTotalBytes <= 0 || maxFileBytes > maxTotalBytes) {
            throw new IllegalArgumentException("maxFileBytes must be positive and no greater than maxTotalBytes");
        }
        if (maxEntries <= 0 || maxIncludeDepth <= 0) {
            throw new IllegalArgumentException("maxEntries and maxIncludeDepth must be positive");
        }
    }
}
