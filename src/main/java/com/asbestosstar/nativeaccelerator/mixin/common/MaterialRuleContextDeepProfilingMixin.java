package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.worldgen.SurfaceDeepProfiler;
import com.asbestosstar.nativeaccelerator.worldgen.WorldgenProfiler;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only timing of lazily evaluated surface-rule context helpers. */
@Mixin(MaterialRuleContext.class)
public abstract class MaterialRuleContextDeepProfilingMixin {
    @Unique private static final ThreadLocal<long[]> NATIVEACCELERATOR_BIOME = ThreadLocal.withInitial(() -> new long[2]);
    @Unique private static final ThreadLocal<long[]> NATIVEACCELERATOR_PRELIM = ThreadLocal.withInitial(() -> new long[2]);
    @Unique private static final ThreadLocal<long[]> NATIVEACCELERATOR_SECONDARY = ThreadLocal.withInitial(() -> new long[2]);

    @Inject(method = "getBiome", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$biomeBegin(CallbackInfoReturnable<Holder<Biome>> cir) {
        nativeaccelerator$begin(NATIVEACCELERATOR_BIOME);
    }

    @Inject(method = "getBiome", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$biomeEnd(CallbackInfoReturnable<Holder<Biome>> cir) {
        nativeaccelerator$end(NATIVEACCELERATOR_BIOME, 0);
    }

    @Inject(method = "getMinSurfaceLevel", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$preliminaryBegin(CallbackInfoReturnable<Integer> cir) {
        nativeaccelerator$begin(NATIVEACCELERATOR_PRELIM);
    }

    @Inject(method = "getMinSurfaceLevel", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$preliminaryEnd(CallbackInfoReturnable<Integer> cir) {
        nativeaccelerator$end(NATIVEACCELERATOR_PRELIM, 1);
    }

    @Inject(method = "getSurfaceSecondary", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$secondaryBegin(CallbackInfoReturnable<Double> cir) {
        nativeaccelerator$begin(NATIVEACCELERATOR_SECONDARY);
    }

    @Inject(method = "getSurfaceSecondary", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$secondaryEnd(CallbackInfoReturnable<Double> cir) {
        nativeaccelerator$end(NATIVEACCELERATOR_SECONDARY, 2);
    }

    @Unique
    private static void nativeaccelerator$begin(ThreadLocal<long[]> timer) {
        long[] t = timer.get();
        t[0] = System.nanoTime();
        t[1] = WorldgenProfiler.beginCpu();
    }

    @Unique
    private static void nativeaccelerator$end(ThreadLocal<long[]> timer, int kind) {
        long[] t = timer.get();
        long wallStart = t[0];
        if (wallStart == 0L) return;
        long wall = System.nanoTime() - wallStart;
        long cpu = -1L;
        if (t[1] >= 0L) {
            long endCpu = WorldgenProfiler.beginCpu();
            if (endCpu >= t[1]) cpu = endCpu - t[1];
        }
        switch (kind) {
            case 0 -> SurfaceDeepProfiler.addBiome(wall, cpu);
            case 1 -> SurfaceDeepProfiler.addPreliminary(wall, cpu);
            case 2 -> SurfaceDeepProfiler.addSecondary(wall, cpu);
            default -> { }
        }
        t[0] = 0L;
        t[1] = -1L;
    }
}
