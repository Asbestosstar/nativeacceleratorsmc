package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.startup.StartupStages;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.client.renderer.texture.Stitcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measures vanilla texture-atlas packing separately from resource reload.
 *
 * <p>The timing is intentionally observational. Atlas placement controls every sprite coordinate,
 * so packing algorithm replacements require validation against representative large resource packs.
 * This stage makes that workload visible without changing ordering, placements, dimensions, or error
 * behavior.</p>
 */
@Mixin(Stitcher.class)
public abstract class StitcherThroughputMixin {

    @Inject(method = "stitch", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$stitchBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.CLIENT_ATLAS_STITCH);
    }

    @Inject(method = "stitch", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$stitchEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.CLIENT_ATLAS_STITCH);
    }
}

