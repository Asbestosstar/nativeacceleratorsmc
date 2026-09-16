package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.startup.StartupStages;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Startup timing for {@code net.minecraft.client.Minecraft}.
 *
 * <p>Three parts of client startup live here:</p>
 * <ul>
 *   <li>construction ({@code Minecraft(GameConfig)}) - the client object, its resource manager and its
 *       window/GL setup;</li>
 *   <li>the game loop ({@code run()}), recorded as a <em>lifetime</em> stage because it ends only when the
 *       game is closed;</li>
 *   <li>{@code onGameLoadFinished} - the exact point the loading overlay is dismissed and the initial
 *       screen is shown. Marking it gives the headline "time to a playable client" and triggers the first
 *       report, so the numbers are visible in the log without quitting.</li>
 * </ul>
 *
 * <p>The {@code client.loading} stage opened by {@code LoadingOverlayMixin} is closed here and by that
 * mixin's {@code tick}, whichever runs first; {@code StartupTimer} makes the second close a no-op.</p>
 *
 * <p>The {@code client.resource-reload} stage is <em>not</em> started here: it is owned by
 * {@code ResourceLoadStateTrackerMixin}, which sees {@code startReload}/{@code finishReload} directly.</p>
 *
 * <p>Reversibility: every injection uses {@code require = 0}; the handlers call only {@link StartupTimer},
 * which never throws and is disabled by {@code -Dnativeaccelerator.startup.timing=false}.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftStartupMixin {

    @Inject(method = "<init>(Lnet/minecraft/client/main/GameConfig;)V", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$minecraftInitBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.CLIENT_MINECRAFT_INIT);
    }

    @Inject(method = "<init>(Lnet/minecraft/client/main/GameConfig;)V", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$minecraftInitEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.CLIENT_MINECRAFT_INIT);
    }

    @Inject(method = "run()V", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$gameLoopBegin(CallbackInfo ci) {
        StartupTimer.beginLifetime(StartupStages.CLIENT_GAME_LOOP);
    }

    @Inject(method = "onGameLoadFinished", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$gameLoadFinished(CallbackInfo ci) {
        StartupTimer.end(StartupStages.CLIENT_LOADING);
        StartupTimer.mark(StartupStages.CLIENT_TITLE_SCREEN);
        StartupTimer.finish("client load finished (first screen shown)");
    }
}
