package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.worldgen.SurfaceDeepProfiler;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/** Expensive diagnostic-only breakdown of MaterialSystem work, sampled in the per-block rule hot path. */
@Mixin(MaterialSystem.class)
public abstract class MaterialSystemDeepProfilingMixin {
    @Inject(method = "buildSurface", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$surfaceDeepBegin(RandomState randomState, BiomeManager biomeManager,
            WorldGenerationContext generationContext, ChunkAccess protoChunk, NoiseChunk noiseChunk,
            MaterialRule ruleSource, Set<Holder<Biome>> possibleBiomes, CallbackInfo ci) {
        SurfaceDeepProfiler.beginSurface();
    }

    @Inject(method = "buildSurface", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$surfaceDeepEnd(RandomState randomState, BiomeManager biomeManager,
            WorldGenerationContext generationContext, ChunkAccess protoChunk, NoiseChunk noiseChunk,
            MaterialRule ruleSource, Set<Holder<Biome>> possibleBiomes, CallbackInfo ci) {
        SurfaceDeepProfiler.endSurface();
    }

    @Redirect(method = "buildSurface", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/material/rule/RuleEvaluator;tryApply(III)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState nativeaccelerator$profileRuleApply(RuleEvaluator evaluator, int x, int y, int z) {
        boolean sample = SurfaceDeepProfiler.beginRule();
        BlockState result = evaluator.tryApply(x, y, z);
        SurfaceDeepProfiler.endRule(sample);
        return result;
    }
}

