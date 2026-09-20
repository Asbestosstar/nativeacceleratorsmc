package com.asbestosstar.nativeaccelerator.mixin.client;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes RenderPearl's OpenGL sampler LOD behavior match its Vulkan backend (and Native
 * Accelerator's Metal backend).
 *
 * <p>Vanilla Vulkan chooses nearest-vs-linear mip interpolation from {@code maxLod > 0.25} and
 * clamps max LOD to at least 0.25.  Vanilla OpenGL always selected one of the two
 * *_MIPMAP_LINEAR minification modes and only programmed GL_TEXTURE_MAX_LOD when the optional was
 * present.  That is a real backend discrepancy and is particularly visible when small atlas
 * renderings sample texture-atlas edges.</p>
 */
@Mixin(GlSampler.class)
public abstract class GlSamplerVulkanParityMixin {
    @Shadow @Final private int id;

    private static final boolean NATIVEACCELERATOR_SAMPLER_PARITY = Boolean.parseBoolean(
            System.getProperty("nativeaccelerator.renderer.opengl.samplerVulkanParity", "true"));
    private static final AtomicBoolean NATIVEACCELERATOR_REPORTED = new AtomicBoolean();

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$matchVulkanMipSemantics(
            AddressMode addressModeU,
            AddressMode addressModeV,
            FilterMode minFilter,
            FilterMode magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod,
            CallbackInfo ci) {
        if (!NATIVEACCELERATOR_SAMPLER_PARITY) return;

        double requestedMaxLod = maxLod.orElse(1000.0);
        boolean linearMipInterpolation = requestedMaxLod > 0.25;

        int minFilterValue;
        if (minFilter == FilterMode.NEAREST) {
            minFilterValue = linearMipInterpolation
                    ? GL33C.GL_NEAREST_MIPMAP_LINEAR
                    : GL33C.GL_NEAREST_MIPMAP_NEAREST;
        } else {
            minFilterValue = linearMipInterpolation
                    ? GL33C.GL_LINEAR_MIPMAP_LINEAR
                    : GL33C.GL_LINEAR_MIPMAP_NEAREST;
        }

        GL33C.glSamplerParameteri(this.id, GL33C.GL_TEXTURE_MIN_FILTER, minFilterValue);
        GL33C.glSamplerParameterf(this.id, GL33C.GL_TEXTURE_MAX_LOD,
                Math.max(0.25f, (float) requestedMaxLod));

        if (NATIVEACCELERATOR_REPORTED.compareAndSet(false, true)) {
            System.out.println("[Native Accelerator] OpenGL sampler parity active: Vulkan-compatible mip mode/maxLod");
        }
    }
}
