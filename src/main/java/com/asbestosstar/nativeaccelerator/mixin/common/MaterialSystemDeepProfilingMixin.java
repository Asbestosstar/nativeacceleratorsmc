package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.worldgen.SurfaceDeepProfiler;
import com.asbestosstar.nativeaccelerator.worldgen.WorldgenProfiler;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/** Expensive diagnostic-only breakdown of MaterialSystem surface rule work. */
@Mixin(MaterialSystem.class)
public abstract class MaterialSystemDeepProfilingMixin {

    @Inject(method = "buildSurface", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$surfaceDeepBegin(RandomState randomState, BiomeManager biomeManager,
                                                     WorldGenerationContext generationContext, ChunkAccess protoChunk,
                                                     NoiseChunk noiseChunk, MaterialRule ruleSource,
                                                     Set<Holder<Biome>> possibleBiomes, CallbackInfo ci) {
        SurfaceDeepProfiler.beginSurface();
    }

    @Inject(method = "buildSurface", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$surfaceDeepEnd(RandomState randomState, BiomeManager biomeManager,
                                                   WorldGenerationContext generationContext, ChunkAccess protoChunk,
                                                   NoiseChunk noiseChunk, MaterialRule ruleSource,
                                                   Set<Holder<Biome>> possibleBiomes, CallbackInfo ci) {
        SurfaceDeepProfiler.endSurface();
    }

    @Redirect(method = "buildSurface", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;compile(Lnet/minecraft/world/level/levelgen/material/MaterialRuleContext;)Lnet/minecraft/world/level/levelgen/material/rule/RuleEvaluator;"))
    private RuleEvaluator nativeaccelerator$profileCompile(MaterialRule rule, MaterialRuleContext context) {
        long wall = System.nanoTime();
        long cpu = WorldgenProfiler.beginCpu();
        RuleEvaluator result = rule.compile(context);
        SurfaceDeepProfiler.addCompile(System.nanoTime() - wall, nativeaccelerator$cpuElapsed(cpu));
        return result;
    }

    @Redirect(method = "buildSurface", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/material/rule/RuleEvaluator;tryApply(III)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState nativeaccelerator$profileRuleApply(RuleEvaluator evaluator, int x, int y, int z) {
        long wall = System.nanoTime();
        long cpu = WorldgenProfiler.beginCpu();
        BlockState result = evaluator.tryApply(x, y, z);
        SurfaceDeepProfiler.addRule(System.nanoTime() - wall, nativeaccelerator$cpuElapsed(cpu));
        return result;
    }

    @Unique
    private static long nativeaccelerator$cpuElapsed(long start) {
        if (start < 0L) return -1L;
        long end = WorldgenProfiler.beginCpu();
        return end >= start ? end - start : -1L;
    }
}
