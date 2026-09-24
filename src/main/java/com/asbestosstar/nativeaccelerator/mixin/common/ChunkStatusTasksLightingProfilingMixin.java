package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.worldgen.WorldgenProfiler;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Diagnostic-only timing of the synchronous skylight-source initialization before light-engine submission. */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksLightingProfilingMixin {
    @Redirect(method = "initializeLight", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;initializeLightSources()V"))
    private static void nativeaccelerator$profileSourceScan(ChunkAccess chunk) {
        long wall = WorldgenProfiler.begin();
        long cpu = WorldgenProfiler.beginCpu();
        chunk.initializeLightSources();
        WorldgenProfiler.recordPhaseCpu("lighting.initializeLightSources", wall, cpu);
    }
}

