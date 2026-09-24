package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.renderer.metal.MetalBackend;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Integrates the METAL enum value installed by {@code PreferredGraphicsApiEnumHook} with
 * RenderPearl's backend selection. The enum extension is what makes Metal visible in the GUI;
 * this mixin decides which concrete backend instances to try for that selection.
 */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMetalMixin {
    /** Explicit Metal selection: try Metal first and retain OpenGL as a boot-safe fallback. */
    @Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
    private void nativeaccelerator$selectMetal(CallbackInfoReturnable<GpuBackend[]> cir) {
        PreferredGraphicsApi self = (PreferredGraphicsApi) (Object) this;
        if (!"metal".equals(self.getSerializedName())) {
            return;
        }
        cir.setReturnValue(new GpuBackend[]{new MetalBackend(), new GlBackend()});
    }

    /** Default/automatic selection: prefer Metal when SDL reports that it is usable. */
    @Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
    private void nativeaccelerator$offerMetalForDefault(CallbackInfoReturnable<GpuBackend[]> cir) {
        PreferredGraphicsApi self = (PreferredGraphicsApi) (Object) this;
        if (self != PreferredGraphicsApi.DEFAULT || !MetalBackend.shouldOffer()) {
            return;
        }

        GpuBackend[] vanilla = cir.getReturnValue();
        GpuBackend[] withMetal = new GpuBackend[vanilla.length + 1];
        withMetal[0] = new MetalBackend();
        System.arraycopy(vanilla, 0, withMetal, 1, vanilla.length);
        cir.setReturnValue(withMetal);
    }
}

