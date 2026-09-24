package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.kernels.NoiseAcceleration;
import net.minecraft.SharedConstants;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.GradientNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Coarse-grained worldgen interception for PerlinNoise.addToVolume.
 *
 * <p>Only large volumes are replaced. If the native library/capability is absent, the volume is too
 * small, staging fails, or the native call fails, the injected method returns normally and vanilla's
 * Java implementation executes unchanged.</p>
 */
@Mixin(PerlinNoise.class)
public abstract class PerlinNoiseMixin extends GradientNoise {
    protected PerlinNoiseMixin(RandomSource random) {
        super(random);
    }

    @Inject(method = "addToVolume", at = @At("HEAD"), cancellable = true, require = 0)
    private void nativeaccelerator$addToVolume(DensityBuffer buffer,
                                               DensityVolume volume,
                                               double xzScale,
                                               double yScale,
                                               float amplitude,
                                               CallbackInfo ci) {
        float[] values = ((DensityBufferAccessor) (Object) buffer).nativeaccelerator$values();
        if (NoiseAcceleration.addPerlinVolume(values, buffer.size(),
                volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                xzScale, yScale, amplitude,
                this.perms,
                this.offsetX, this.offsetY, this.offsetZ,
                !SharedConstants.DEBUG_ENABLE_FARLANDS)) {
            ci.cancel();
        }
    }
}

