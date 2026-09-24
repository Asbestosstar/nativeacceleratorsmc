package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.renderer.metal.GraphicsBackendPreference;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Includes Native Accelerator's Metal preference in Minecraft's existing restart-required warning. */
@Mixin(Options.class)
public abstract class OptionsMetalRestartMixin {
    @Shadow
    public abstract net.minecraft.client.OptionInstance<net.minecraft.client.PreferredGraphicsApi> preferredGraphicsBackend();

    @Inject(method = "isRestartRequiredToApplyVideoSettings", at = @At("RETURN"), cancellable = true, require = 0)
    private void nativeaccelerator$metalRestartRequired(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        if (GraphicsBackendPreference.changedSinceStartup(this.preferredGraphicsBackend().get())) {
            cir.setReturnValue(true);
        }
    }
}

