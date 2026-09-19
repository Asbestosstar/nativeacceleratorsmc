package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.worldgen.WorldgenProfiler;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Synchronous subphase timings inside NoiseBasedChunkGenerator.buildTerrain's background task. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorProfilingMixin {
    @Unique private static final ThreadLocal<Long> BUILD_TERRAIN = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> CREATE_NOISE = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> FILL = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> SURFACE = new ThreadLocal<>();
    @Unique private static final ThreadLocal<Long> CARVERS = new ThreadLocal<>();

    @Inject(method = "buildTerrain", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$terrainBegin(ChunkAccess chunk, Blender blender, RandomState randomState,
                                                StructureManager structureManager, BiomeManager biomeManager,
                                                WorldGenRegion carverBiomeRegion,
                                                Set<Holder<Biome>> possibleBiomes,
                                                CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (WorldgenProfiler.enabled()) BUILD_TERRAIN.set(System.nanoTime());
    }

    @Inject(method = "buildTerrain", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$terrainFuture(ChunkAccess chunk, Blender blender, RandomState randomState,
                                                 StructureManager structureManager, BiomeManager biomeManager,
                                                 WorldGenRegion carverBiomeRegion,
                                                 Set<Holder<Biome>> possibleBiomes,
                                                 CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!WorldgenProfiler.enabled()) return;
        Long start = BUILD_TERRAIN.get();
        BUILD_TERRAIN.remove();
        CompletableFuture<ChunkAccess> future = cir.getReturnValue();
        if (start != null && future != null) {
            future.whenComplete((result, error) -> WorldgenProfiler.recordPhase("terrain.total", start));
        }
    }

    @Inject(method = "createNoiseChunk", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$createNoiseBegin(ChunkAccess chunk, StructureManager structureManager,
                                                     Blender blender, RandomState randomState, NoiseSettings noiseSettings,
                                                     CallbackInfoReturnable<NoiseChunk> cir) {
        if (WorldgenProfiler.enabled()) CREATE_NOISE.set(System.nanoTime());
    }

    @Inject(method = "createNoiseChunk", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$createNoiseEnd(ChunkAccess chunk, StructureManager structureManager,
                                                   Blender blender, RandomState randomState, NoiseSettings noiseSettings,
                                                   CallbackInfoReturnable<NoiseChunk> cir) {
        nativeaccelerator$finishPhase(CREATE_NOISE, "terrain.createNoiseChunk");
    }

    @Inject(method = "doFill", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$fillBegin(NoiseChunk noiseChunk, ChunkAccess chunk, CallbackInfo ci) {
        if (WorldgenProfiler.enabled()) FILL.set(System.nanoTime());
    }

    @Inject(method = "doFill", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$fillEnd(NoiseChunk noiseChunk, ChunkAccess chunk, CallbackInfo ci) {
        nativeaccelerator$finishPhase(FILL, "terrain.doFill");
    }

    @Inject(method = "buildSurface", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$surfaceBegin(ChunkAccess chunk, NoiseChunk noiseChunk, RandomState randomState,
                                                BiomeManager biomeManager, Set<Holder<Biome>> possibleBiomes,
                                                MaterialRule materialRule, CallbackInfo ci) {
        if (WorldgenProfiler.enabled()) SURFACE.set(System.nanoTime());
    }

    @Inject(method = "buildSurface", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$surfaceEnd(ChunkAccess chunk, NoiseChunk noiseChunk, RandomState randomState,
                                              BiomeManager biomeManager, Set<Holder<Biome>> possibleBiomes,
                                              MaterialRule materialRule, CallbackInfo ci) {
        nativeaccelerator$finishPhase(SURFACE, "terrain.buildSurface");
    }

    @Inject(method = "generateCarvers", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$carversBegin(ChunkAccess chunk, Blender blender, NoiseChunk noiseChunk,
                                                RandomState randomState, BiomeManager biomeManager,
                                                WorldGenRegion carverBiomeRegion, MaterialRule materialRule,
                                                CallbackInfo ci) {
        if (WorldgenProfiler.enabled()) CARVERS.set(System.nanoTime());
    }

    @Inject(method = "generateCarvers", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$carversEnd(ChunkAccess chunk, Blender blender, NoiseChunk noiseChunk,
                                              RandomState randomState, BiomeManager biomeManager,
                                              WorldGenRegion carverBiomeRegion, MaterialRule materialRule,
                                              CallbackInfo ci) {
        nativeaccelerator$finishPhase(CARVERS, "terrain.generateCarvers");
    }

    @Unique
    private static void nativeaccelerator$finishPhase(ThreadLocal<Long> timer, String name) {
        if (!WorldgenProfiler.enabled()) return;
        Long start = timer.get();
        timer.remove();
        if (start != null) WorldgenProfiler.recordPhase(name, start);
    }
}
