package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelLoadBatcher;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Replaces one-future-per-model loading with the high-throughput work-stealing pipeline. */
@Mixin(ModelManager.class)
public abstract class ModelManagerBatchLoadingMixin {
    @Inject(method = "loadBlockModels", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nativeaccelerator$batchRawModels(
            ResourceManager manager,
            Executor executor,
            CallbackInfoReturnable<CompletableFuture<Map<Identifier, UnbakedModel>>> cir) {
        if (ModelLoadBatcher.enabled()) cir.setReturnValue(ModelLoadBatcher.loadBlockModels(manager, executor));
    }
}
