package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.worldgen.WorldgenProfiler;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Measures actual asynchronous completion time for each generated ChunkStatus. */
@Mixin(ChunkStep.class)
public abstract class ChunkStepProfilingMixin {
    @Unique private static final ThreadLocal<Long> NATIVEACCELERATOR_START = new ThreadLocal<>();

    @Shadow @Final private ChunkStatus targetStatus;

    @Inject(method = "apply", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$statusBegin(WorldGenContext context,
                                                StaticCache2D<GenerationChunkHolder> cache,
                                                ChunkAccess chunk,
                                                CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!WorldgenProfiler.enabled()) return;
        if (chunk.getPersistedStatus().isBefore(this.targetStatus)) {
            NATIVEACCELERATOR_START.set(System.nanoTime());
        } else {
            NATIVEACCELERATOR_START.remove();
        }
    }

    @Inject(method = "apply", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$statusFuture(WorldGenContext context,
                                                 StaticCache2D<GenerationChunkHolder> cache,
                                                 ChunkAccess chunk,
                                                 CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!WorldgenProfiler.enabled()) return;
        Long start = NATIVEACCELERATOR_START.get();
        NATIVEACCELERATOR_START.remove();
        if (start == null || cir.getReturnValue() == null) return;
        String status = this.targetStatus.getName();
        cir.getReturnValue().whenComplete((result, error) ->
                WorldgenProfiler.recordStatus(status, start, error == null));
    }
}
