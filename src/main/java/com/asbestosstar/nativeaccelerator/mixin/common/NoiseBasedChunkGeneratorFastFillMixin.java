package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.worldgen.WorldgenProfiler;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Section-oriented replacement for NoiseBasedChunkGenerator#doFill.
 *
 * <p>The vanilla loop performs ChunkAccess#getSectionIndex/getSection plus DensityVolume coordinate/index
 * helpers for every generated voxel. This path preserves the exact aquifer -> default block -> section
 * write -> two heightmap updates -> fluid post-processing sequence, but hoists section lookup to once per
 * 16-block vertical band and reads the already-materialized DensityBuffer float[] linearly.</p>
 *
 * <p>Only ordinary unit-step chunk volumes are intercepted. Debug aquifer visualization and any unusual
 * stepped volume fall through to vanilla unchanged.</p>
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorFastFillMixin {
    @Unique private static final boolean NATIVEACCELERATOR_FAST_FILL =
            NativeAcceleratorConfig.booleanValue("worldgen.fastFill", true);
    @Unique private static final BlockState NATIVEACCELERATOR_AIR = Blocks.AIR.defaultBlockState();

    @Shadow @Final private Holder<NoiseGeneratorSettings> settings;

    @Inject(method = "doFill", at = @At("HEAD"), cancellable = true, require = 0)
    private void nativeaccelerator$fastFill(NoiseChunk noiseChunk, ChunkAccess chunk, CallbackInfo ci) {
        if (!NATIVEACCELERATOR_FAST_FILL || SharedConstants.DEBUG_AQUIFERS) return;

        DensityVolume volume = noiseChunk.volume();
        if (volume.stepBlockX() != 1 || volume.stepBlockY() != 1 || volume.stepBlockZ() != 1) return;
        if (volume.sizeX() != 16 || volume.sizeZ() != 16) return;

        long started = WorldgenProfiler.begin();
        long cpuStarted = WorldgenProfiler.beginCpu();

        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Aquifer aquifer = noiseChunk.aquifer();
        BlockState defaultBlock = this.settings.value().defaultBlock();
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        DensitySampler.Bound finalDensity = noiseChunk.cachingSamplers().get(this.settings.value().noiseRouter().finalDensity());

        final int sizeX = volume.sizeX();
        final int sizeY = volume.sizeY();
        final int sizeZ = volume.sizeZ();
        final int minBlockX = volume.minBlockX();
        final int minBlockY = volume.minBlockY();
        final int minBlockZ = volume.minBlockZ();
        final long cells = (long) sizeX * sizeY * sizeZ;

        try (ScopedDensityBuffer densityBuffer = finalDensity.sampleVolume(volume)) {
            float[] densityValues = ((DensityBufferAccessor) (Object) densityBuffer).nativeaccelerator$values();

            for (int z = 0; z < sizeZ; ++z) {
                int blockZ = minBlockZ + z;
                for (int x = 0; x < sizeX; ++x) {
                    int blockX = minBlockX + x;
                    int y = sizeY - 1;
                    int densityIndex = (x + z * sizeX) * sizeY + y;

                    while (y >= 0) {
                        int topBlockY = minBlockY + y;
                        int sectionIndex = chunk.getSectionIndex(topBlockY);
                        LevelChunkSection section = chunk.getSection(sectionIndex);
                        int sectionMinBlockY = SectionPos.sectionToBlockCoord(
                                chunk.getSectionYFromSectionIndex(sectionIndex));
                        int lowestYInBand = Math.max(0, sectionMinBlockY - minBlockY);

                        for (; y >= lowestYInBand; --y, --densityIndex) {
                            int blockY = minBlockY + y;
                            float density = densityValues[densityIndex];
                            BlockState state = aquifer.computeSubstance(blockX, blockY, blockZ, density);
                            if (state == null) state = defaultBlock;
                            if (state == NATIVEACCELERATOR_AIR) continue;

                            section.setBlockState(x, blockY & 15, z, state, false);
                            oceanFloor.update(x, blockY, z, state);
                            worldSurface.update(x, blockY, z, state);

                            // This flag is updated by computeSubstance, so it deliberately remains per voxel.
                            if (!aquifer.shouldScheduleFluidUpdate() || state.getFluidState().isEmpty()) continue;
                            blockPos.set(blockX, blockY, blockZ);
                            chunk.markPosForPostProcessing(blockPos);
                        }
                    }
                }
            }
        }

        if (started != 0L) {
            WorldgenProfiler.recordPhaseCpu("terrain.fastFill", started, cpuStarted, cells);
        }
        ci.cancel();
    }
}

