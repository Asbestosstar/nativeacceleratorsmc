package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.worldgen.WorldgenProfiler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Measures bulk density sampling and reports the number of sampled cells. */
@Mixin(DensitySampler.Bound.class)
public abstract class DensitySamplerBoundProfilingMixin {
    @Unique private static final ThreadLocal<long[]> NATIVEACCELERATOR_START = ThreadLocal.withInitial(() -> new long[2]);

    @Inject(method = "sampleVolume(Lnet/minecraft/world/level/levelgen/densityfunction/DensityVolume;)Lnet/minecraft/world/level/levelgen/densityfunction/ScopedDensityBuffer;",
            at = @At("HEAD"), require = 0)
    private void nativeaccelerator$densityBegin(DensityVolume volume,
                                                 CallbackInfoReturnable<ScopedDensityBuffer> cir) {
        if (!WorldgenProfiler.enabled()) return;
        long[] t = NATIVEACCELERATOR_START.get();
        t[0] = System.nanoTime();
        t[1] = WorldgenProfiler.beginCpu();
    }

    @Inject(method = "sampleVolume(Lnet/minecraft/world/level/levelgen/densityfunction/DensityVolume;)Lnet/minecraft/world/level/levelgen/densityfunction/ScopedDensityBuffer;",
            at = @At("RETURN"), require = 0)
    private void nativeaccelerator$densityEnd(DensityVolume volume,
                                               CallbackInfoReturnable<ScopedDensityBuffer> cir) {
        if (!WorldgenProfiler.enabled()) return;
        long[] t = NATIVEACCELERATOR_START.get();
        long start = t[0];
        if (start == 0L) return;
        long cells;
        try { cells = Math.multiplyExact(Math.multiplyExact((long) volume.sizeX(), volume.sizeY()), volume.sizeZ()); }
        catch (ArithmeticException ignored) { cells = 0L; }
        WorldgenProfiler.recordPhaseCpu("density.sampleVolume", start, t[1], cells);
        t[0] = 0L;
        t[1] = -1L;
    }
}

