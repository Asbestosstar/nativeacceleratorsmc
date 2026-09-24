package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.startup.StartupStages;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Separates FireBlock's rule-table population from the outer bootstrap measurement.
 *
 * <p>This is deliberately observational: it neither changes flammability values nor replaces
 * vanilla's registration order. The outer {@code bootstrap.fire-block} stage includes the first
 * {@code Blocks.FIRE} access, while this nested stage measures the 194 vanilla
 * {@code setFlammable(block, igniteOdds, burnOdds)} registrations alone.</p>
 */
@Mixin(FireBlock.class)
public abstract class FireBlockBootstrapTimingMixin {

    @Inject(method = "bootStrap", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$rulesBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.BOOTSTRAP_FIRE_RULES);
    }

    @Inject(method = "bootStrap", at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$rulesEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.BOOTSTRAP_FIRE_RULES);
    }
}

