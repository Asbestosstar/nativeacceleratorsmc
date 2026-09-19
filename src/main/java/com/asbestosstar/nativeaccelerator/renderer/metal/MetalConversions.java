package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;

/** RenderPearl -> SDL GPU enum/flag conversions shared by the Metal backend. */
final class MetalConversions {
    private MetalConversions() {}

    static int textureFormat(GpuFormat format) {
        String constant = switch (format) {
            case R8_UNORM -> "SDL_GPU_TEXTUREFORMAT_R8_UNORM";
            case R8_SNORM -> "SDL_GPU_TEXTUREFORMAT_R8_SNORM";
            case RG8_UNORM -> "SDL_GPU_TEXTUREFORMAT_R8G8_UNORM";
            case RG8_SNORM -> "SDL_GPU_TEXTUREFORMAT_R8G8_SNORM";
            case RGBA8_UNORM -> "SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM";
            case RGBA8_SNORM -> "SDL_GPU_TEXTUREFORMAT_R8G8B8A8_SNORM";
            case R16_UNORM -> "SDL_GPU_TEXTUREFORMAT_R16_UNORM";
            case R16_SNORM -> "SDL_GPU_TEXTUREFORMAT_R16_SNORM";
            case RG16_UNORM -> "SDL_GPU_TEXTUREFORMAT_R16G16_UNORM";
            case RG16_SNORM -> "SDL_GPU_TEXTUREFORMAT_R16G16_SNORM";
            case RGBA16_UNORM -> "SDL_GPU_TEXTUREFORMAT_R16G16B16A16_UNORM";
            case RGBA16_SNORM -> "SDL_GPU_TEXTUREFORMAT_R16G16B16A16_SNORM";
            case R8_UINT -> "SDL_GPU_TEXTUREFORMAT_R8_UINT";
            case R8_SINT -> "SDL_GPU_TEXTUREFORMAT_R8_INT";
            case RG8_UINT -> "SDL_GPU_TEXTUREFORMAT_R8G8_UINT";
            case RG8_SINT -> "SDL_GPU_TEXTUREFORMAT_R8G8_INT";
            case RGBA8_UINT -> "SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UINT";
            case RGBA8_SINT -> "SDL_GPU_TEXTUREFORMAT_R8G8B8A8_INT";
            case R16_UINT -> "SDL_GPU_TEXTUREFORMAT_R16_UINT";
            case R16_SINT -> "SDL_GPU_TEXTUREFORMAT_R16_INT";
            case RG16_UINT -> "SDL_GPU_TEXTUREFORMAT_R16G16_UINT";
            case RG16_SINT -> "SDL_GPU_TEXTUREFORMAT_R16G16_INT";
            case RGBA16_UINT -> "SDL_GPU_TEXTUREFORMAT_R16G16B16A16_UINT";
            case RGBA16_SINT -> "SDL_GPU_TEXTUREFORMAT_R16G16B16A16_INT";
            case R32_UINT -> "SDL_GPU_TEXTUREFORMAT_R32_UINT";
            case R32_SINT -> "SDL_GPU_TEXTUREFORMAT_R32_INT";
            case RG32_UINT -> "SDL_GPU_TEXTUREFORMAT_R32G32_UINT";
            case RG32_SINT -> "SDL_GPU_TEXTUREFORMAT_R32G32_INT";
            case RGBA32_UINT -> "SDL_GPU_TEXTUREFORMAT_R32G32B32A32_UINT";
            case RGBA32_SINT -> "SDL_GPU_TEXTUREFORMAT_R32G32B32A32_INT";
            case R16_FLOAT -> "SDL_GPU_TEXTUREFORMAT_R16_FLOAT";
            case RG16_FLOAT -> "SDL_GPU_TEXTUREFORMAT_R16G16_FLOAT";
            case RGBA16_FLOAT -> "SDL_GPU_TEXTUREFORMAT_R16G16B16A16_FLOAT";
            case R32_FLOAT -> "SDL_GPU_TEXTUREFORMAT_R32_FLOAT";
            case RG32_FLOAT -> "SDL_GPU_TEXTUREFORMAT_R32G32_FLOAT";
            case RGBA32_FLOAT -> "SDL_GPU_TEXTUREFORMAT_R32G32B32A32_FLOAT";
            case RGB10A2_UNORM -> "SDL_GPU_TEXTUREFORMAT_R10G10B10A2_UNORM";
            case RGB10A2_UINT -> "SDL_GPU_TEXTUREFORMAT_R10G10B10A2_UINT";
            case RG11B10_FLOAT -> "SDL_GPU_TEXTUREFORMAT_R11G11B10_UFLOAT";
            case D32_FLOAT -> "SDL_GPU_TEXTUREFORMAT_D32_FLOAT";
            case D32_FLOAT_S8_UINT -> "SDL_GPU_TEXTUREFORMAT_D32_FLOAT_S8_UINT";
            case D24_UNORM_S8_UINT -> "SDL_GPU_TEXTUREFORMAT_D24_UNORM_S8_UINT";
            case D16_UNORM -> "SDL_GPU_TEXTUREFORMAT_D16_UNORM";
            // SDL GPU intentionally has no standalone stencil-only target in the portable set.
            case S8_UINT -> throw unsupportedTexture(format);
            // 3-component texture formats are not portable SDL GPU texture formats. They remain valid
            // vertex formats and are handled in vertexFormat().
            case RGB8_UNORM, RGB8_SNORM, RGB16_UNORM, RGB16_SNORM,
                    RGB8_UINT, RGB8_SINT, RGB16_UINT, RGB16_SINT,
                    RGB32_UINT, RGB32_SINT, RGB16_FLOAT, RGB32_FLOAT -> throw unsupportedTexture(format);
        };
        return MetalInterop.sdl(constant);
    }

    static int vertexFormat(GpuFormat format) {
        String constant = switch (format) {
            case R32_SINT -> "SDL_GPU_VERTEXELEMENTFORMAT_INT";
            case RG32_SINT -> "SDL_GPU_VERTEXELEMENTFORMAT_INT2";
            case RGB32_SINT -> "SDL_GPU_VERTEXELEMENTFORMAT_INT3";
            case RGBA32_SINT -> "SDL_GPU_VERTEXELEMENTFORMAT_INT4";
            case R32_UINT -> "SDL_GPU_VERTEXELEMENTFORMAT_UINT";
            case RG32_UINT -> "SDL_GPU_VERTEXELEMENTFORMAT_UINT2";
            case RGB32_UINT -> "SDL_GPU_VERTEXELEMENTFORMAT_UINT3";
            case RGBA32_UINT -> "SDL_GPU_VERTEXELEMENTFORMAT_UINT4";
            case R32_FLOAT -> "SDL_GPU_VERTEXELEMENTFORMAT_FLOAT";
            case RG32_FLOAT -> "SDL_GPU_VERTEXELEMENTFORMAT_FLOAT2";
            case RGB32_FLOAT -> "SDL_GPU_VERTEXELEMENTFORMAT_FLOAT3";
            case RGBA32_FLOAT -> "SDL_GPU_VERTEXELEMENTFORMAT_FLOAT4";
            case RG8_SINT -> "SDL_GPU_VERTEXELEMENTFORMAT_BYTE2";
            case RGBA8_SINT -> "SDL_GPU_VERTEXELEMENTFORMAT_BYTE4";
            case RG8_UINT -> "SDL_GPU_VERTEXELEMENTFORMAT_UBYTE2";
            case RGBA8_UINT -> "SDL_GPU_VERTEXELEMENTFORMAT_UBYTE4";
            case RG8_SNORM -> "SDL_GPU_VERTEXELEMENTFORMAT_BYTE2_NORM";
            case RGBA8_SNORM -> "SDL_GPU_VERTEXELEMENTFORMAT_BYTE4_NORM";
            case RG8_UNORM -> "SDL_GPU_VERTEXELEMENTFORMAT_UBYTE2_NORM";
            case RGBA8_UNORM -> "SDL_GPU_VERTEXELEMENTFORMAT_UBYTE4_NORM";
            case RG16_SINT -> "SDL_GPU_VERTEXELEMENTFORMAT_SHORT2";
            case RGBA16_SINT -> "SDL_GPU_VERTEXELEMENTFORMAT_SHORT4";
            case RG16_UINT -> "SDL_GPU_VERTEXELEMENTFORMAT_USHORT2";
            case RGBA16_UINT -> "SDL_GPU_VERTEXELEMENTFORMAT_USHORT4";
            case RG16_SNORM -> "SDL_GPU_VERTEXELEMENTFORMAT_SHORT2_NORM";
            case RGBA16_SNORM -> "SDL_GPU_VERTEXELEMENTFORMAT_SHORT4_NORM";
            case RG16_UNORM -> "SDL_GPU_VERTEXELEMENTFORMAT_USHORT2_NORM";
            case RGBA16_UNORM -> "SDL_GPU_VERTEXELEMENTFORMAT_USHORT4_NORM";
            case RG16_FLOAT -> "SDL_GPU_VERTEXELEMENTFORMAT_HALF2";
            case RGBA16_FLOAT -> "SDL_GPU_VERTEXELEMENTFORMAT_HALF4";
            default -> throw new IllegalArgumentException("Unsupported SDL GPU vertex format: " + format);
        };
        return MetalInterop.sdl(constant);
    }

    static int primitive(PrimitiveTopology topology) {
        return MetalInterop.sdl(switch (topology) {
            case TRIANGLES, QUADS -> "SDL_GPU_PRIMITIVETYPE_TRIANGLELIST";
            case TRIANGLE_STRIP -> "SDL_GPU_PRIMITIVETYPE_TRIANGLESTRIP";
            case LINES, DEBUG_LINES -> "SDL_GPU_PRIMITIVETYPE_LINELIST";
            case DEBUG_LINE_STRIP -> "SDL_GPU_PRIMITIVETYPE_LINESTRIP";
            case POINTS -> "SDL_GPU_PRIMITIVETYPE_POINTLIST";
            case TRIANGLE_FAN -> throw new IllegalArgumentException("SDL GPU has no triangle-fan primitive; RenderPearl must triangulate it before Metal");
        });
    }

    static int indexType(IndexType type) {
        return MetalInterop.sdl(type == IndexType.SHORT
                ? "SDL_GPU_INDEXELEMENTSIZE_16BIT" : "SDL_GPU_INDEXELEMENTSIZE_32BIT");
    }

    static int fillMode(PolygonMode mode) {
        return MetalInterop.sdl(mode == PolygonMode.WIREFRAME ? "SDL_GPU_FILLMODE_LINE" : "SDL_GPU_FILLMODE_FILL");
    }

    static int compare(CompareOp op) {
        return MetalInterop.sdl(switch (op) {
            case ALWAYS_PASS -> "SDL_GPU_COMPAREOP_ALWAYS";
            case LESS_THAN -> "SDL_GPU_COMPAREOP_LESS";
            case LESS_THAN_OR_EQUAL -> "SDL_GPU_COMPAREOP_LESS_OR_EQUAL";
            case EQUAL -> "SDL_GPU_COMPAREOP_EQUAL";
            case NOT_EQUAL -> "SDL_GPU_COMPAREOP_NOT_EQUAL";
            case GREATER_THAN_OR_EQUAL -> "SDL_GPU_COMPAREOP_GREATER_OR_EQUAL";
            case GREATER_THAN -> "SDL_GPU_COMPAREOP_GREATER";
            case NEVER_PASS -> "SDL_GPU_COMPAREOP_NEVER";
        });
    }

    static int blendOp(BlendOp op) {
        return MetalInterop.sdl(switch (op) {
            case ADD -> "SDL_GPU_BLENDOP_ADD";
            case SUBTRACT -> "SDL_GPU_BLENDOP_SUBTRACT";
            case REVERSE_SUBTRACT -> "SDL_GPU_BLENDOP_REVERSE_SUBTRACT";
            case MIN -> "SDL_GPU_BLENDOP_MIN";
            case MAX -> "SDL_GPU_BLENDOP_MAX";
        });
    }

    static int blendFactor(BlendFactor factor) {
        return MetalInterop.sdl(switch (factor) {
            case ZERO -> "SDL_GPU_BLENDFACTOR_ZERO";
            case ONE -> "SDL_GPU_BLENDFACTOR_ONE";
            case SRC_COLOR -> "SDL_GPU_BLENDFACTOR_SRC_COLOR";
            case ONE_MINUS_SRC_COLOR -> "SDL_GPU_BLENDFACTOR_ONE_MINUS_SRC_COLOR";
            case DST_COLOR -> "SDL_GPU_BLENDFACTOR_DST_COLOR";
            case ONE_MINUS_DST_COLOR -> "SDL_GPU_BLENDFACTOR_ONE_MINUS_DST_COLOR";
            case SRC_ALPHA -> "SDL_GPU_BLENDFACTOR_SRC_ALPHA";
            case ONE_MINUS_SRC_ALPHA -> "SDL_GPU_BLENDFACTOR_ONE_MINUS_SRC_ALPHA";
            case DST_ALPHA -> "SDL_GPU_BLENDFACTOR_DST_ALPHA";
            case ONE_MINUS_DST_ALPHA -> "SDL_GPU_BLENDFACTOR_ONE_MINUS_DST_ALPHA";
            case CONSTANT_COLOR, CONSTANT_ALPHA -> "SDL_GPU_BLENDFACTOR_CONSTANT_COLOR";
            case ONE_MINUS_CONSTANT_COLOR, ONE_MINUS_CONSTANT_ALPHA -> "SDL_GPU_BLENDFACTOR_ONE_MINUS_CONSTANT_COLOR";
            case SRC_ALPHA_SATURATE -> "SDL_GPU_BLENDFACTOR_SRC_ALPHA_SATURATE";
        });
    }

    static int samplerAddress(AddressMode mode) {
        return MetalInterop.sdl(mode == AddressMode.REPEAT
                ? "SDL_GPU_SAMPLERADDRESSMODE_REPEAT" : "SDL_GPU_SAMPLERADDRESSMODE_CLAMP_TO_EDGE");
    }

    static int filter(FilterMode mode) {
        return MetalInterop.sdl(mode == FilterMode.LINEAR ? "SDL_GPU_FILTER_LINEAR" : "SDL_GPU_FILTER_NEAREST");
    }

    static int textureUsage(int usage, GpuFormat format) {
        int result = 0;
        if ((usage & 4) != 0) result |= MetalInterop.sdl("SDL_GPU_TEXTUREUSAGE_SAMPLER");
        if ((usage & 8) != 0) {
            result |= MetalInterop.sdl(format.hasDepthAspect() || format.hasStencilAspect()
                    ? "SDL_GPU_TEXTUREUSAGE_DEPTH_STENCIL_TARGET" : "SDL_GPU_TEXTUREUSAGE_COLOR_TARGET");
        }
        // Copy source/destination are implicit capabilities in SDL GPU and do not have usage bits.
        if (result == 0) result = MetalInterop.sdl("SDL_GPU_TEXTUREUSAGE_SAMPLER");
        return result;
    }

    static int bufferUsage(int usage) {
        int result = 0;
        if ((usage & 32) != 0) result |= MetalInterop.sdl("SDL_GPU_BUFFERUSAGE_VERTEX");
        if ((usage & 64) != 0) result |= MetalInterop.sdl("SDL_GPU_BUFFERUSAGE_INDEX");
        if ((usage & 512) != 0) result |= MetalInterop.sdl("SDL_GPU_BUFFERUSAGE_INDIRECT");
        // RenderPearl texel buffers are lowered to read-only Metal storage buffers. SDL requires the
        // GRAPHICS_STORAGE_READ usage bit for buffers passed to SDL_BindGPU*StorageBuffers.
        if ((usage & 256) != 0) result |= MetalInterop.sdl("SDL_GPU_BUFFERUSAGE_GRAPHICS_STORAGE_READ");
        // Uniform data is pushed from the CPU shadow. Uniform-only/copy-only allocations still need
        // a concrete SDL GPU backing usage so they can be copied/mapped through transfer buffers.
        if (result == 0) result = MetalInterop.sdl("SDL_GPU_BUFFERUSAGE_GRAPHICS_STORAGE_READ");
        return result;
    }

    private static IllegalArgumentException unsupportedTexture(GpuFormat format) {
        return new IllegalArgumentException("GpuFormat " + format + " has no portable SDL GPU Metal texture equivalent");
    }
}
