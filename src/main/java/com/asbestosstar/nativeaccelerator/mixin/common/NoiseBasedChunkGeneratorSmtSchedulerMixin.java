package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.worldgen.WorldgenWorkScheduler;
import net.minecraft.TracingExecutor;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.Executor;

/** Routes asynchronous terrain construction through the topology-aware SMT pool on high-SMT hosts. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorSmtSchedulerMixin {
    @Redirect(method = "buildTerrain", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/TracingExecutor;forName(Ljava/lang/String;)Ljava/util/concurrent/Executor;"))
    private Executor nativeaccelerator$worldgenExecutor(TracingExecutor vanilla, String name) {
        return WorldgenWorkScheduler.enabled() ? WorldgenWorkScheduler.executor() : vanilla.forName(name);
    }
}

