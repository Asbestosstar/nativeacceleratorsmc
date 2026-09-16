package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.startup.StartupStages;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times the single-player world load: {@code net.minecraft.client.server.IntegratedServer#initServer()}.
 *
 * <p>Loading a world is the second half of what a player waits for, and it is not covered by the client
 * probes: the title screen appears before any world is opened. This is the client counterpart of
 * {@link com.asbestosstar.nativeaccelerator.mixin.server.DedicatedServerReadyMixin}, so "load a world"
 * can be compared between single-player and a dedicated server.</p>
 *
 * <p>The mixin targets {@code IntegratedServer}, which only exists on a client, and lives in
 * {@code ...mixin.client} so {@code SystemMixinGate.environmentRule} skips it on a dedicated server
 * instead of failing to link {@code net.minecraft.client.server.IntegratedServer}.</p>
 *
 * <p>The stage is ranked, not a milestone: a world can be opened more than once, and {@link StartupTimer}
 * keeps the first occurrence. It is closed at {@code RETURN}, so the duration is the load itself and not
 * the whole server lifetime.</p>
 *
 * <p>Reversibility: both injections use {@code require = 0}; the handlers call only {@link StartupTimer},
 * which never throws and is disabled by {@code -Dnativeaccelerator.startup.timing=false}.</p>
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {

    @Inject(method = "initServer()Z", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$integratedServerBegin(CallbackInfoReturnable<Boolean> cir) {
        StartupTimer.begin(StartupStages.CLIENT_INTEGRATED_SERVER);
    }

    @Inject(method = "initServer()Z", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$integratedServerEnd(CallbackInfoReturnable<Boolean> cir) {
        StartupTimer.end(StartupStages.CLIENT_INTEGRATED_SERVER);
    }
}
