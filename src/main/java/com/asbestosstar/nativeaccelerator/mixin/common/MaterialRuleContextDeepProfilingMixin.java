package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.worldgen.SurfaceDeepProfiler;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnostic-only sampled timing of lazily evaluated surface-rule context helpers. */
@Mixin(MaterialRuleContext.class)
public abstract class MaterialRuleContextDeepProfilingMixin {
    @Unique private static final ThreadLocal<boolean[]> NATIVEACCELERATOR_SAMPLES =
            ThreadLocal.withInitial(() -> new boolean[3]);

    @Inject(method = "getBiome", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$biomeBegin(CallbackInfoReturnable<Holder<Biome>> cir) {
        NATIVEACCELERATOR_SAMPLES.get()[0] = SurfaceDeepProfiler.beginBiome();
    }
    @Inject(method = "getBiome", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$biomeEnd(CallbackInfoReturnable<Holder<Biome>> cir) {
        SurfaceDeepProfiler.endBiome(NATIVEACCELERATOR_SAMPLES.get()[0]);
    }
    @Inject(method = "getMinSurfaceLevel", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$preliminaryBegin(CallbackInfoReturnable<Integer> cir) {
        NATIVEACCELERATOR_SAMPLES.get()[1] = SurfaceDeepProfiler.beginPreliminary();
    }
    @Inject(method = "getMinSurfaceLevel", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$preliminaryEnd(CallbackInfoReturnable<Integer> cir) {
        SurfaceDeepProfiler.endPreliminary(NATIVEACCELERATOR_SAMPLES.get()[1]);
    }
    @Inject(method = "getSurfaceSecondary", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$secondaryBegin(CallbackInfoReturnable<Double> cir) {
        NATIVEACCELERATOR_SAMPLES.get()[2] = SurfaceDeepProfiler.beginSecondary();
    }
    @Inject(method = "getSurfaceSecondary", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$secondaryEnd(CallbackInfoReturnable<Double> cir) {
        SurfaceDeepProfiler.endSecondary(NATIVEACCELERATOR_SAMPLES.get()[2]);
    }
}
