package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelDagProfiler;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Wall-clock probe for asynchronous raw model loading; no result or scheduling is changed. */
@Mixin(ModelManager.class)
public abstract class ModelManagerPhaseProfilingMixin {
    @Unique private static final ThreadLocal<Long> NATIVEACCELERATOR_RAW_LOAD_STARTED = new ThreadLocal<>();

    @Inject(method = "loadBlockModels", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$startRawModelLoad(ResourceManager resources, Executor executor,
            CallbackInfoReturnable<CompletableFuture<Map<?, ?>>> cir) {
        NATIVEACCELERATOR_RAW_LOAD_STARTED.set(System.nanoTime());
    }

    @Inject(method = "loadBlockModels", at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$finishRawModelLoad(ResourceManager resources, Executor executor,
            CallbackInfoReturnable<CompletableFuture<Map<?, ?>>> cir) {
        Long started = NATIVEACCELERATOR_RAW_LOAD_STARTED.get();
        NATIVEACCELERATOR_RAW_LOAD_STARTED.remove();
        if (started == null) return;
        ModelDagProfiler.track("raw-models", cir.getReturnValue(), started);
        cir.getReturnValue().whenComplete((result, failure) -> {
            if (failure == null) StartupTimer.recordDuration("client.model-manager.phase.raw-model-load.wall", System.nanoTime() - started);
        });
    }
}
