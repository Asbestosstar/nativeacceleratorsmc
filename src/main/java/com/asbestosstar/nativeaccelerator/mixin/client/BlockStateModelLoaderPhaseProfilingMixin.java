package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelDagProfiler;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Wall-clock probe for asynchronous blockstate definition loading. */
@Mixin(BlockStateModelLoader.class)
public abstract class BlockStateModelLoaderPhaseProfilingMixin {
    @Unique private static final ThreadLocal<Long> NATIVEACCELERATOR_BLOCKSTATE_LOAD_STARTED = new ThreadLocal<>();

    @Inject(method = "loadBlockStates", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$startBlockStateLoad(ResourceManager resources, Executor executor,
            CallbackInfoReturnable<CompletableFuture<BlockStateModelLoader.LoadedModels>> cir) {
        NATIVEACCELERATOR_BLOCKSTATE_LOAD_STARTED.set(System.nanoTime());
    }

    @Inject(method = "loadBlockStates", at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$finishBlockStateLoad(ResourceManager resources, Executor executor,
            CallbackInfoReturnable<CompletableFuture<BlockStateModelLoader.LoadedModels>> cir) {
        Long started = NATIVEACCELERATOR_BLOCKSTATE_LOAD_STARTED.get();
        NATIVEACCELERATOR_BLOCKSTATE_LOAD_STARTED.remove();
        if (started == null) return;
        ModelDagProfiler.track("blockstates", cir.getReturnValue(), started);
        cir.getReturnValue().whenComplete((result, failure) -> {
            if (failure == null) StartupTimer.recordDuration("client.model-manager.phase.blockstate-load.wall", System.nanoTime() - started);
        });
    }
}
