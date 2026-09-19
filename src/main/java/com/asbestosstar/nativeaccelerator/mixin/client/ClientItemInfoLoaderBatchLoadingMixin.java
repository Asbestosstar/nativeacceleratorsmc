package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ClientItemLoadBatcher;
import net.minecraft.client.resources.model.ClientItemInfoLoader;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Replaces ClientItemInfoLoader's one-future-per-item path with the shared work-stealing pipeline. */
@Mixin(ClientItemInfoLoader.class)
public abstract class ClientItemInfoLoaderBatchLoadingMixin {
    @Inject(method = "scheduleLoad", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nativeaccelerator$batchItems(ResourceManager manager, Executor executor,
            CallbackInfoReturnable<CompletableFuture<ClientItemInfoLoader.LoadedClientInfos>> cir) {
        if (ClientItemLoadBatcher.enabled()) cir.setReturnValue(ClientItemLoadBatcher.scheduleLoad(manager, executor));
    }
}
