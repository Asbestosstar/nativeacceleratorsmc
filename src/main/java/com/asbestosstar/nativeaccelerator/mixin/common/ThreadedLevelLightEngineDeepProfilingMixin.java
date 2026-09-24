package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.worldgen.WorldgenProfiler;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic-only split of light-engine batches into queued pre-work, propagation and post-work. */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineDeepProfilingMixin {
    @Unique private static final ThreadLocal<long[]> NATIVEACCELERATOR_LIGHT = ThreadLocal.withInitial(() -> new long[4]);

    @Inject(method = "runUpdate", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$batchBegin(CallbackInfo ci) {
        long[] s = NATIVEACCELERATOR_LIGHT.get();
        s[0] = s[2] = System.nanoTime();
        s[1] = s[3] = WorldgenProfiler.beginCpu();
    }

    @Inject(method = "runUpdate",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I",
                    shift = At.Shift.BEFORE), require = 0)
    private void nativeaccelerator$beforePropagation(CallbackInfo ci) {
        long[] s = NATIVEACCELERATOR_LIGHT.get();
        nativeaccelerator$recordPhase(s, "lighting.preTasks");
    }

    @Inject(method = "runUpdate",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I",
                    shift = At.Shift.AFTER), require = 0)
    private void nativeaccelerator$afterPropagation(CallbackInfo ci) {
        long[] s = NATIVEACCELERATOR_LIGHT.get();
        nativeaccelerator$recordPhase(s, "lighting.propagation");
    }

    @Inject(method = "runUpdate", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$batchEnd(CallbackInfo ci) {
        long[] s = NATIVEACCELERATOR_LIGHT.get();
        nativeaccelerator$recordPhase(s, "lighting.postTasks");
        if (s[0] != 0L) {
            WorldgenProfiler.recordPhaseCpu("lighting.runUpdate.total", s[0], s[1]);
        }
        s[0] = 0L; s[1] = -1L; s[2] = 0L; s[3] = -1L;
    }

    @Unique
    private static void nativeaccelerator$recordPhase(long[] s, String name) {
        if (s[2] == 0L) return;
        WorldgenProfiler.recordPhaseCpu(name, s[2], s[3]);
        s[2] = System.nanoTime();
        s[3] = WorldgenProfiler.beginCpu();
    }

}

