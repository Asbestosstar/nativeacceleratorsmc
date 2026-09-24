package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Experimental surface-height cache fast path: cache WORLD_SURFACE_WG heights once per 16x16 chunk.
 *
 * <p>Vanilla buildSurface asks for the same height repeatedly for starting height, loop height and both
 * gradient directions. Capturing the 256 values once turns the remaining queries into primitive-array
 * reads. This path is OFF by default because special surface extensions can write blocks while the chunk
 * is being processed; enable it only for parity/performance experiments until that mutation interaction is
 * fully validated.</p>
 */
@Mixin(MaterialSystem.class)
public abstract class MaterialSystemFastHeightMixin {
    @Unique private static final boolean NATIVEACCELERATOR_FAST_SURFACE =
            NativeAcceleratorConfig.booleanValue("worldgen.fastSurface", false);
    @Unique private static final ThreadLocal<HeightCache> NATIVEACCELERATOR_HEIGHTS =
            ThreadLocal.withInitial(HeightCache::new);

    @Inject(method = "buildSurface", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$captureHeights(RandomState randomState, BiomeManager biomeManager,
                                                   WorldGenerationContext generationContext, ChunkAccess protoChunk,
                                                   NoiseChunk noiseChunk, MaterialRule ruleSource,
                                                   Set<Holder<Biome>> possibleBiomes, CallbackInfo ci) {
        if (!NATIVEACCELERATOR_FAST_SURFACE) return;
        NATIVEACCELERATOR_HEIGHTS.get().capture(protoChunk);
    }

    @Inject(method = "buildSurface", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$clearHeights(RandomState randomState, BiomeManager biomeManager,
                                                 WorldGenerationContext generationContext, ChunkAccess protoChunk,
                                                 NoiseChunk noiseChunk, MaterialRule ruleSource,
                                                 Set<Holder<Biome>> possibleBiomes, CallbackInfo ci) {
        if (NATIVEACCELERATOR_FAST_SURFACE) NATIVEACCELERATOR_HEIGHTS.get().clear();
    }

    @Redirect(method = "buildSurface", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private int nativeaccelerator$cachedHeight(ChunkAccess chunk, Heightmap.Types type, int x, int z) {
        HeightCache cache = NATIVEACCELERATOR_HEIGHTS.get();
        if (NATIVEACCELERATOR_FAST_SURFACE && type == Heightmap.Types.WORLD_SURFACE_WG && cache.matches(chunk)) {
            return cache.get(x, z);
        }
        return chunk.getHeight(type, x, z);
    }

    @Inject(method = "getSurfaceGradientX", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nativeaccelerator$gradientX(ChunkAccess chunk, int x, int z,
                                                     CallbackInfoReturnable<Integer> cir) {
        if (!NATIVEACCELERATOR_FAST_SURFACE) return;
        HeightCache cache = NATIVEACCELERATOR_HEIGHTS.get();
        if (!cache.matches(chunk)) return;
        cir.setReturnValue(cache.get(Math.min(x + 1, 15), z) - cache.get(Math.max(x - 1, 0), z));
    }

    @Inject(method = "getSurfaceGradientZ", at = @At("HEAD"), cancellable = true, require = 0)
    private static void nativeaccelerator$gradientZ(ChunkAccess chunk, int x, int z,
                                                     CallbackInfoReturnable<Integer> cir) {
        if (!NATIVEACCELERATOR_FAST_SURFACE) return;
        HeightCache cache = NATIVEACCELERATOR_HEIGHTS.get();
        if (!cache.matches(chunk)) return;
        cir.setReturnValue(cache.get(x, Math.min(z + 1, 15)) - cache.get(x, Math.max(z - 1, 0)));
    }

    @Unique
    private static final class HeightCache {
        private final int[] heights = new int[16 * 16];
        private ChunkAccess chunk;

        void capture(ChunkAccess chunk) {
            this.chunk = chunk;
            for (int z = 0; z < 16; ++z) {
                for (int x = 0; x < 16; ++x) {
                    heights[x + z * 16] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                }
            }
        }

        boolean matches(ChunkAccess chunk) {
            return this.chunk == chunk;
        }

        int get(int x, int z) {
            return heights[x + z * 16];
        }

        void clear() {
            this.chunk = null;
        }
    }
}

