package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.ModelJsonParseCache;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Makes parsed-model reuse strictly reload-scoped and reports the JSON portion of ModelManager work.
 *
 * <p>All resource-pack changes construct a new ModelManager reload. Clearing at its head guarantees
 * that parsed definitions never cross a reload boundary. Completion callbacks observe parallel
 * preparation work without changing ordering or executor selection.</p>
 */
@Mixin(ModelManager.class)
public abstract class ModelManagerReloadProfilingMixin {
    private static final String PREFIX = "client.model-manager.json.";

    @Inject(method = "reload", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$beginModelReload(
            PreparableReloadListener.SharedState state,
            java.util.concurrent.Executor preparationExecutor,
            PreparableReloadListener.PreparationBarrier barrier,
            java.util.concurrent.Executor applyExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        ModelJsonParseCache.beginReload();
    }

    @Inject(method = "reload", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$recordModelJsonWork(
            PreparableReloadListener.SharedState state,
            java.util.concurrent.Executor preparationExecutor,
            PreparableReloadListener.PreparationBarrier barrier,
            java.util.concurrent.Executor applyExecutor,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        cir.getReturnValue().whenComplete((ignored, failure) -> {
            if (failure != null) return;
            ModelJsonParseCache.Snapshot snapshot = ModelJsonParseCache.snapshot();
            StartupTimer.recordDuration(PREFIX + "read", snapshot.readNanos());
            StartupTimer.recordDuration(PREFIX + "parse", snapshot.parseNanos());
            System.out.printf("[Native Accelerator] ModelManager JSON: read=%.1f ms parse=%.1f ms hits=%d misses=%d unique=%d%n",
                    snapshot.readNanos() / 1_000_000.0,
                    snapshot.parseNanos() / 1_000_000.0,
                    snapshot.hits(), snapshot.misses(), snapshot.entries());
        });
    }
}
