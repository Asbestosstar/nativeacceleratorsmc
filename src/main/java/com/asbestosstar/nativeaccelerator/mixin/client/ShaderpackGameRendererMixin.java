package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.renderer.universal.ShaderpackPostProcessRuntime;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the selected, prepared fixture after world rendering and before the GUI pass. */
@Mixin(GameRenderer.class)
public abstract class ShaderpackGameRendererMixin {
    @Inject(method = "applyPostEffects()V", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$applyShaderpack(CallbackInfo ci) {
        ShaderpackPostProcessRuntime.renderMainTarget((GameRenderer) (Object) this);
    }
}
