package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.startup.StartupStages;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the moment the loading overlay is created and opens the visible {@code client.loading} stage.
 *
 * <p>The overlay is constructed just before the initial resource reload is handed to it, so the milestone
 * recorded here is the best available "the game is now visibly loading" instant, and the time between it
 * and the first title screen is what a player perceives as start-up time. The same stage is closed by
 * {@code MinecraftStartupMixin} at {@code onGameLoadFinished}; {@link StartupTimer} makes whichever close
 * happens second a no-op.</p>
 *
 * <p>Reversibility: {@code require = 0}, and the handler only calls {@link StartupTimer}.</p>
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$overlayCreated(CallbackInfo ci) {
        StartupTimer.mark(StartupStages.CLIENT_LOADING_OVERLAY);
        StartupTimer.begin(StartupStages.CLIENT_LOADING);
    }
}

