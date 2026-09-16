package com.asbestosstar.nativeaccelerator.mixin.server;

import com.asbestosstar.nativeaccelerator.startup.StartupStages;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.server.MinecraftServer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the instant a <em>dedicated</em> server starts accepting players.
 *
 * <p>The only source location that writes {@code MinecraftServer.isReady = true} is the shared
 * {@code MinecraftServer#runServer()}, so both the dedicated server and the client's single-player
 * integrated server reach it. A single-player world load is not a "dedicated server ready" event, and
 * printing the report there would interrupt the client while it is still loading. The probe therefore
 * lives in {@code ...mixin.server}, which {@code SystemMixinGate.environmentRule} applies only when no
 * client is present; the integrated server is marked separately by
 * {@code ...mixin.client.IntegratedServerMixin}.</p>
 *
 * <p><b>This injection runs on every server tick.</b> The assignment sits at the end of the
 * {@code while (this.running)} body, so this handler fires roughly twenty times a second for as long as
 * the server runs, not once. That is safe only because both calls are idempotent:
 * {@link StartupTimer#mark(String)} keeps the first instant, and {@link StartupTimer#finish(String)}
 * reports on its first invocation only. The run-once policy deliberately lives in {@link StartupTimer}
 * rather than here, so this Mixin stays a seam that calls nothing but the timer.</p>
 *
 * <p>Timing is anchored to the field write rather than to a log line, so the milestone cannot drift if
 * the surrounding messages change. Reversibility: {@code require = 0} - if the field is renamed, the
 * dedicated server still starts and only this one number is missing.</p>
 */
@Mixin(MinecraftServer.class)
public abstract class DedicatedServerReadyMixin {

    @Inject(method = "runServer()V", require = 0,
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                    target = "Lnet/minecraft/server/MinecraftServer;isReady:Z"))
    private void nativeaccelerator$serverReady(CallbackInfo ci) {
        StartupTimer.mark(StartupStages.SERVER_READY);
        StartupTimer.finish("dedicated server ready");
    }
}
