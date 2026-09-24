package com.asbestosstar.nativeaccelerator.mixin.server;

import com.asbestosstar.nativeaccelerator.worldgen.WorldgenWorkScheduler;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives the dedicated server control/tick thread the first physical core reserved from bulk worldgen. */
@Mixin(MinecraftServer.class)
public abstract class DedicatedServerAffinityMixin {
    @Inject(method = "runServer", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$bindServerThread(CallbackInfo ci) {
        WorldgenWorkScheduler.bindServerThread();
    }
}

