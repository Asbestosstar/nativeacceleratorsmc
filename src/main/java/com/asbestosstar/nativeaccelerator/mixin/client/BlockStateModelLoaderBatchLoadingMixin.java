package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelLoadBatcher;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Replaces one-future-per-blockstate loading with the high-throughput work-stealing pipeline. */
@Mixin(BlockStateModelLoader.class)
public abstract class BlockStateModelLoaderBatchLoadingMixin {
    @Inject(method = "loadBlockStates", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nativeaccelerator$batchBlockStates(
            ResourceManager manager,
            Executor executor,
            CallbackInfoReturnable<CompletableFuture<BlockStateModelLoader.LoadedModels>> cir) {
        if (ModelLoadBatcher.enabled()) cir.setReturnValue(ModelLoadBatcher.loadBlockStates(manager, executor));
    }
}

