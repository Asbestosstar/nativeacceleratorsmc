package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelDagProfiler;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Records the wall-clock span of the parallel ModelBakery bake phase without altering its work. */
@Mixin(ModelBakery.class)
public abstract class ModelBakeryPhaseProfilingMixin {
    @Unique private long nativeaccelerator$bakeStartedNanos;

    @Inject(method = "bakeModels", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$startBake(MaterialBaker materials, Executor executor,
            CallbackInfoReturnable<CompletableFuture<ModelBakery.BakingResult>> cir) {
        nativeaccelerator$bakeStartedNanos = System.nanoTime();
    }

    @Inject(method = "bakeModels", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$finishBake(MaterialBaker materials, Executor executor,
            CallbackInfoReturnable<CompletableFuture<ModelBakery.BakingResult>> cir) {
        long started = nativeaccelerator$bakeStartedNanos;
        ModelDagProfiler.track("model-bakery.bake", cir.getReturnValue(), started);
        cir.getReturnValue().whenComplete((result, failure) -> {
            if (failure == null && started != 0L) {
                StartupTimer.recordDuration("client.model-manager.phase.bake.wall", System.nanoTime() - started);
            }
        });
    }
}
