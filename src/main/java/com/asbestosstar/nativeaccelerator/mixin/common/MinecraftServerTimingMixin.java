package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.startup.StartupStages;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Startup timing for {@code net.minecraft.server.MinecraftServer}, the base class shared by the dedicated
 * server and by the single-player integrated server, so one mixin covers both.
 *
 * <ul>
 *   <li>{@code runServer()} as a whole - a <em>lifetime</em> stage, since it only returns when the server
 *       stops;</li>
 *   <li>the {@code initServer()} call inside it - the world/level load that happens before the first tick,
 *       which is the dominant part of "time until the server is usable". {@code initServer} is abstract
 *       on {@code MinecraftServer} and implemented by {@code DedicatedServer} and {@code IntegratedServer};
 *       timing the call site measures both without a mixin per subclass.</li>
 * </ul>
 *
 * <p>The "server is up" milestone is deliberately <em>not</em> handled here. The sole write of
 * {@code isReady = true} happens in this shared method for both server flavours, so marking it here would
 * report a single-player integrated server as a "dedicated server ready" event and would print the
 * report while the client is still loading a world. That milestone therefore lives in
 * {@code ...mixin.server.DedicatedServerReadyMixin}, which {@code SystemMixinGate} applies only when no
 * client is present; the client's own integrated server is marked by
 * {@code ...mixin.client.IntegratedServerMixin} instead.</p>
 *
 * <p>Reversibility: every injection uses {@code require = 0}, so a renamed/removed member costs one
 * measurement and never prevents the server from starting.</p>
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTimingMixin {

    @Inject(method = "runServer()V", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$serverRunLoopBegin(CallbackInfo ci) {
        StartupTimer.beginLifetime(StartupStages.SERVER_RUN_LOOP);
    }

    @Inject(method = "runServer()V", require = 0,
            at = @At(value = "INVOKE", shift = Shift.BEFORE,
                    target = "Lnet/minecraft/server/MinecraftServer;initServer()Z"))
    private void nativeaccelerator$serverInitBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.SERVER_INIT);
    }

    @Inject(method = "runServer()V", require = 0,
            at = @At(value = "INVOKE", shift = Shift.AFTER,
                    target = "Lnet/minecraft/server/MinecraftServer;initServer()Z"))
    private void nativeaccelerator$serverInitEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.SERVER_INIT);
    }
}
