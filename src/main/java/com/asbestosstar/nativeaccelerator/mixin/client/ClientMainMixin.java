package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.startup.StartupStages;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the client JVM entrypoint {@code net.minecraft.client.main.Main#main(String[])}.
 *
 * <p>This is the earliest probe that can exist: it starts before argument parsing and before
 * {@code Bootstrap.bootStrap()}, so every other stage and milestone is effectively measured against it.
 * Because {@code main} hands control to the client tick loop and only returns when the game is closed, it
 * is recorded as a <em>lifetime</em> stage and excluded from the ranking and the percentage column.</p>
 *
 * <p>Reversibility and safety: the injection uses {@code require = 0} and the handler only calls
 * {@link StartupTimer}, which never throws and is disabled with
 * {@code -Dnativeaccelerator.startup.timing=false}.</p>
 */
@Mixin(Main.class)
public abstract class ClientMainMixin {

    @Inject(method = "main([Ljava/lang/String;)V", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$clientMainBegin(String[] args, CallbackInfo ci) {
        StartupTimer.beginLifetime(StartupStages.CLIENT_MAIN);
    }
}

