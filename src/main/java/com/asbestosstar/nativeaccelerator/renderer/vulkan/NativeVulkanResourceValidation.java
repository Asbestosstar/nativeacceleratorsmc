package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;

/** Overflow-safe validation shared by Vulkan resource wrappers. */
final class NativeVulkanResourceValidation {
    private NativeVulkanResourceValidation() {
    }

    static void range(long size, long offset, long length, String kind) {
        if (size < 0L || offset < 0L || length <= 0L || offset > size || length > size - offset) {
            throw new IllegalArgumentException(kind + " range is outside the resource");
        }
    }

    static void bufferUsage(int usage) {
        int supported = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE
                | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST
                | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX
                | GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER
                | GpuBuffer.USAGE_INDIRECT_PARAMETERS;
        if ((usage & supported) == 0 || (usage & ~supported) != 0) {
            throw new IllegalArgumentException("Unsupported Vulkan buffer usage bits: 0x"
                    + Integer.toHexString(usage));
        }
    }

    static void map(long size, long offset, long length, boolean read, boolean write,
                    int usage, boolean hostVisible) {
        if (!read && !write) throw new IllegalArgumentException("A buffer map must request read and/or write access");
        if (read && (usage & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalArgumentException("Buffer was not created with map-read usage");
        }
        if (write && (usage & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalArgumentException("Buffer was not created with map-write usage");
        }
        if (!hostVisible) throw new IllegalStateException("Vulkan buffer memory is not host visible");
        range(size, offset, length, "Buffer map");
    }

    static void mipRange(int baseMipLevel, int mipLevels, int totalMipLevels) {
        if (baseMipLevel < 0 || mipLevels < 1 || totalMipLevels < 1
                || baseMipLevel > totalMipLevels - mipLevels) {
            throw new IllegalArgumentException("Texture view mip range is outside the texture");
        }
    }

    static int imageLayers(int depthOrLayers, boolean cubeCompatible) {
        if (depthOrLayers < 1) throw new IllegalArgumentException("depthOrLayers must be positive");
        if (cubeCompatible && depthOrLayers != 6) {
            throw new IllegalArgumentException("Cube-compatible Vulkan textures must have six layers");
        }
        return depthOrLayers;
    }

    static long alignedOffset(long offset, long atomSize) {
        if (offset < 0L || atomSize < 1L) throw new IllegalArgumentException("Invalid alignment");
        return offset - offset % atomSize;
    }

    static long alignedEnd(long end, long atomSize, long allocationSize) {
        if (end < 0L || atomSize < 1L || allocationSize < 0L || end > allocationSize) {
            throw new IllegalArgumentException("Invalid aligned range");
        }
        long remainder = end % atomSize;
        if (remainder == 0L || end == allocationSize) return end;
        long rounded = end + (atomSize - remainder);
        return rounded < end || rounded > allocationSize ? allocationSize : rounded;
    }
}
