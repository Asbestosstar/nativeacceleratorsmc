package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.renderer.metal.MetalBackend;
import com.mojang.renderpearl.api.device.GpuBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds Native Accelerator's SDL3/Metal RenderPearl backend to Minecraft's normal
 * backend fallback chain. Explicit OpenGL/Vulkan user choices are left alone;
 * Metal is offered first only for the DEFAULT choice and only when SDL reports
 * a usable Metal GPU driver.
 */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMetalMixin {
    @Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
    private void nativeaccelerator$offerMetal(CallbackInfoReturnable<GpuBackend[]> cir) {
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
