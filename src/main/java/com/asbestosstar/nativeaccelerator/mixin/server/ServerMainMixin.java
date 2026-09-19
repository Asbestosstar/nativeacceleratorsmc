package com.asbestosstar.nativeaccelerator.mixin.server;

import com.asbestosstar.nativeaccelerator.startup.StartupStages;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.server.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the dedicated server JVM entrypoint {@code net.minecraft.server.Main#main(String[])}.
 *
 * <p>The earliest server-side probe: it starts before argument parsing, before
 * {@code SharedConstants.tryDetectVersion()} and before {@code Bootstrap.bootStrap()}, so the server report
 * can be compared with the client report on the same footing.</p>
 *
 * <p>The body of {@code main} ends by calling {@code MinecraftServer#runServer()} and only returns when
 * the server stops, so this is a <em>lifetime</em> stage and is excluded from the ranking and the
 * percentage column.</p>
 *
 * <p>This mixin lives in {@code mixin.server}, so {@code SystemMixinGate} applies it only when no client is
 * present. Reversibility: {@code require = 0}.</p>
 */
@Mixin(Main.class)
public abstract class ServerMainMixin {

    @Inject(method = "main([Ljava/lang/String;)V", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$serverMainBegin(String[] args, CallbackInfo ci) {
        StartupTimer.beginLifetime(StartupStages.SERVER_MAIN);
    }
}
