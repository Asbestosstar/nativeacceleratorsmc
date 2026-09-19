package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.worldgen.WorldgenProfiler;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fast initial sky-light source scan.
 *
 * <p>Vanilla calls LevelChunkSection#hasOnlyAir from findLowestSourceY for every X/Z column, repeatedly
 * asking the same question about a section up to 256 times. This replacement computes that section fact
 * once, then preserves the exact top-to-bottom edge-occlusion scan for each column.</p>
 */
@Mixin(ChunkSkyLightSources.class)
public abstract class ChunkSkyLightSourcesFastMixin {
    @Unique private static final boolean NATIVEACCELERATOR_FAST_LIGHTING =
            NativeAcceleratorConfig.booleanValue("worldgen.fastLighting", true);
    @Unique private static final BlockState NATIVEACCELERATOR_AIR = Blocks.AIR.defaultBlockState();

    @Shadow @Final private int minY;
    @Shadow @Final private BitStorage heightmap;

    @Inject(method = "fillFrom", at = @At("HEAD"), cancellable = true, require = 0)
    private void nativeaccelerator$fillFrom(ChunkAccess chunk, CallbackInfo ci) {
        if (!NATIVEACCELERATOR_FAST_LIGHTING) return;

        long started = WorldgenProfiler.begin();
        long cpuStarted = WorldgenProfiler.beginCpu();

        int maxSectionIndex = chunk.getHighestFilledSectionIndex();
        if (maxSectionIndex == -1) {
            for (int i = 0; i < 256; ++i) this.heightmap.set(i, 0);
            nativeaccelerator$record(started, cpuStarted, 0L);
            ci.cancel();
            return;
        }

        LevelChunkSection[] sections = chunk.getSections();
        boolean[] onlyAir = new boolean[maxSectionIndex + 1];
        for (int sectionIndex = 0; sectionIndex <= maxSectionIndex; ++sectionIndex) {
            onlyAir[sectionIndex] = sections[sectionIndex].hasOnlyAir();
        }

        long testedBlocks = 0L;
        for (int z = 0; z < 16; ++z) {
            for (int x = 0; x < 16; ++x) {
                int topY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(maxSectionIndex) + 1);
                BlockState topState = NATIVEACCELERATOR_AIR;
                int lowestSourceY = this.minY;

                columnScan:
                for (int sectionIndex = maxSectionIndex; sectionIndex >= 0; --sectionIndex) {
                    if (onlyAir[sectionIndex]) {
                        topState = NATIVEACCELERATOR_AIR;
                        topY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex));
                        continue;
                    }

                    LevelChunkSection section = sections[sectionIndex];
                    for (int y = 15; y >= 0; --y) {
                        BlockState bottomState = section.getBlockState(x, y, z);
                        ++testedBlocks;
                        if (nativeaccelerator$isEdgeOccluded(topState, bottomState)) {
                            lowestSourceY = Math.max(topY, this.minY);
                            break columnScan;
                        }
                        topState = bottomState;
                        --topY;
                    }
                }

                this.heightmap.set(x + z * 16, lowestSourceY - this.minY);
            }
        }

        nativeaccelerator$record(started, cpuStarted, testedBlocks);
        ci.cancel();
    }

    @Unique
    private static boolean nativeaccelerator$isEdgeOccluded(BlockState topState, BlockState bottomState) {
        if (bottomState.getLightDampening() != 0) return true;
        VoxelShape topShape = LightEngine.getOcclusionShape(topState, Direction.DOWN);
        VoxelShape bottomShape = LightEngine.getOcclusionShape(bottomState, Direction.UP);
        return Shapes.faceShapeOccludes(topShape, bottomShape);
    }

    @Unique
    private static void nativeaccelerator$record(long started, long cpuStarted, long testedBlocks) {
        if (started != 0L) {
            WorldgenProfiler.recordPhaseCpu("lighting.skySources", started, cpuStarted, testedBlocks);
        }
    }
}
