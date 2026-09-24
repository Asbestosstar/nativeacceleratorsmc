package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.kernels.NoiseStackAcceleration;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Batches the private NoiseStack.Perlin implementation without linking to the private nested Java type.
 * The DensityBuffer is copied to native memory once at entry, every Perlin layer accumulates into that
 * same buffer, and the result is copied back once at exit.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.synth.NoiseStack$Perlin")
public abstract class NoiseStackPerlinMixin {

    @Inject(method = "addToVolume", at = @At("HEAD"), require = 0)
    private void nativeaccelerator$beginStack(DensityBuffer buffer, DensityVolume volume,
                                               double xzScale, double yScale, float amplitude,
                                               CallbackInfo ci) {
        float[] values = ((DensityBufferAccessor) (Object) buffer).nativeaccelerator$values();
        NoiseStackAcceleration.begin(values, buffer.size(), volume.sizeX(), volume.sizeY(), volume.sizeZ());
    }

    @Redirect(method = "addToVolume", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/synth/Noise;addToVolume(Lnet/minecraft/world/level/levelgen/densityfunction/DensityBuffer;Lnet/minecraft/world/level/levelgen/densityfunction/DensityVolume;DDF)V"))
    private void nativeaccelerator$accumulateLayer(Noise noise,
                                                    DensityBuffer buffer,
                                                    DensityVolume volume,
                                                    double xzScale,
                                                    double yScale,
                                                    float amplitude) {
        if (!NoiseStackAcceleration.active()) {
            noise.addToVolume(buffer, volume, xzScale, yScale, amplitude);
            return;
        }

        if (!(noise instanceof PerlinNoise)) {
            NoiseStackAcceleration.failAndCommit();
            noise.addToVolume(buffer, volume, xzScale, yScale, amplitude);
            return;
        }

        GradientNoiseAccessor state = (GradientNoiseAccessor) noise;
        if (!NoiseStackAcceleration.addLayer(
                volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                xzScale, yScale, amplitude,
                state.nativeaccelerator$permutations(),
                state.nativeaccelerator$offsetX(), state.nativeaccelerator$offsetY(), state.nativeaccelerator$offsetZ(),
                !SharedConstants.DEBUG_ENABLE_FARLANDS)) {
            // addLayer already committed successfully completed native layers before disabling the batch.
            noise.addToVolume(buffer, volume, xzScale, yScale, amplitude);
        }
    }

    @Inject(method = "addToVolume", at = @At("RETURN"), require = 0)
    private void nativeaccelerator$finishStack(DensityBuffer buffer, DensityVolume volume,
                                                double xzScale, double yScale, float amplitude,
                                                CallbackInfo ci) {
        NoiseStackAcceleration.finish();
    }
}

