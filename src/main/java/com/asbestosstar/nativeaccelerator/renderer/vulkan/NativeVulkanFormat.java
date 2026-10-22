package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import org.lwjgl.vulkan.VK10;

/** Explicit RenderPearl-to-Vulkan format conversion; unsupported formats fail closed. */
public final class NativeVulkanFormat {
    private NativeVulkanFormat() {
    }

    public static int vkFormat(GpuFormat format) {
        if (format == null) throw new NullPointerException("format");
        return switch (format) {
            case R8_UNORM -> VK10.VK_FORMAT_R8_UNORM;
            case R8_SNORM -> VK10.VK_FORMAT_R8_SNORM;
            case RG8_UNORM -> VK10.VK_FORMAT_R8G8_UNORM;
            case RG8_SNORM -> VK10.VK_FORMAT_R8G8_SNORM;
            case RGB8_UNORM -> VK10.VK_FORMAT_R8G8B8_UNORM;
            case RGB8_SNORM -> VK10.VK_FORMAT_R8G8B8_SNORM;
            case RGBA8_UNORM -> VK10.VK_FORMAT_R8G8B8A8_UNORM;
            case RGBA8_SNORM -> VK10.VK_FORMAT_R8G8B8A8_SNORM;
            case R16_UNORM -> VK10.VK_FORMAT_R16_UNORM;
            case R16_SNORM -> VK10.VK_FORMAT_R16_SNORM;
            case RG16_UNORM -> VK10.VK_FORMAT_R16G16_UNORM;
            case RG16_SNORM -> VK10.VK_FORMAT_R16G16_SNORM;
            case RGB16_UNORM -> VK10.VK_FORMAT_R16G16B16_UNORM;
            case RGB16_SNORM -> VK10.VK_FORMAT_R16G16B16_SNORM;
            case RGBA16_UNORM -> VK10.VK_FORMAT_R16G16B16A16_UNORM;
            case RGBA16_SNORM -> VK10.VK_FORMAT_R16G16B16A16_SNORM;
            case R8_UINT -> VK10.VK_FORMAT_R8_UINT;
            case R8_SINT -> VK10.VK_FORMAT_R8_SINT;
            case RG8_UINT -> VK10.VK_FORMAT_R8G8_UINT;
            case RG8_SINT -> VK10.VK_FORMAT_R8G8_SINT;
            case RGB8_UINT -> VK10.VK_FORMAT_R8G8B8_UINT;
            case RGB8_SINT -> VK10.VK_FORMAT_R8G8B8_SINT;
            case RGBA8_UINT -> VK10.VK_FORMAT_R8G8B8A8_UINT;
            case RGBA8_SINT -> VK10.VK_FORMAT_R8G8B8A8_SINT;
            case R16_UINT -> VK10.VK_FORMAT_R16_UINT;
            case R16_SINT -> VK10.VK_FORMAT_R16_SINT;
            case RG16_UINT -> VK10.VK_FORMAT_R16G16_UINT;
            case RG16_SINT -> VK10.VK_FORMAT_R16G16_SINT;
            case RGB16_UINT -> VK10.VK_FORMAT_R16G16B16_UINT;
            case RGB16_SINT -> VK10.VK_FORMAT_R16G16B16_SINT;
            case RGBA16_UINT -> VK10.VK_FORMAT_R16G16B16A16_UINT;
            case RGBA16_SINT -> VK10.VK_FORMAT_R16G16B16A16_SINT;
            case R32_UINT -> VK10.VK_FORMAT_R32_UINT;
            case R32_SINT -> VK10.VK_FORMAT_R32_SINT;
            case RG32_UINT -> VK10.VK_FORMAT_R32G32_UINT;
            case RG32_SINT -> VK10.VK_FORMAT_R32G32_SINT;
            case RGB32_UINT -> VK10.VK_FORMAT_R32G32B32_UINT;
            case RGB32_SINT -> VK10.VK_FORMAT_R32G32B32_SINT;
            case RGBA32_UINT -> VK10.VK_FORMAT_R32G32B32A32_UINT;
            case RGBA32_SINT -> VK10.VK_FORMAT_R32G32B32A32_SINT;
            case R16_FLOAT -> VK10.VK_FORMAT_R16_SFLOAT;
            case RG16_FLOAT -> VK10.VK_FORMAT_R16G16_SFLOAT;
            case RGB16_FLOAT -> VK10.VK_FORMAT_R16G16B16_SFLOAT;
            case RGBA16_FLOAT -> VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
            case R32_FLOAT -> VK10.VK_FORMAT_R32_SFLOAT;
            case RG32_FLOAT -> VK10.VK_FORMAT_R32G32_SFLOAT;
            case RGB32_FLOAT -> VK10.VK_FORMAT_R32G32B32_SFLOAT;
            case RGBA32_FLOAT -> VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
            case RGB10A2_UNORM -> VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case RGB10A2_UINT -> VK10.VK_FORMAT_A2B10G10R10_UINT_PACK32;
            case RG11B10_FLOAT -> VK10.VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            case D32_FLOAT -> VK10.VK_FORMAT_D32_SFLOAT;
            case D32_FLOAT_S8_UINT -> VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
            case D24_UNORM_S8_UINT -> VK10.VK_FORMAT_D24_UNORM_S8_UINT;
            case D16_UNORM -> VK10.VK_FORMAT_D16_UNORM;
            case S8_UINT -> VK10.VK_FORMAT_S8_UINT;
        };
    }
}
