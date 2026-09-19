package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.kernels.BitStorageAcceleration;
import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Interception seam for {@code net.minecraft.util.SimpleBitStorage.unpack(int[])}.
 *
 * <p>{@code unpack} is the bulk read path for the runtime bit-storage layout: it walks every 64-bit cell and
 * shifts out {@code floor(64 / bits)} complete values per cell. That is exactly the work
 * {@code na_simple_bits_unpack_u32} performs, and this Mixin only replaces the loop when the native backend
 * says so.</p>
 *
 * <p>Reversibility: the injection is {@code HEAD} + {@code cancellable}, and it cancels only when
 * {@link BitStorageAcceleration#unpackSimple} returns {@code true}. With no native library, no
 * {@code PACKED_BITS} capability, an unsupported bit width, or a storage below the size gate, the callback
 * does nothing and the untouched vanilla loop runs. The whole config also honours
 * {@code -Dnativeaccelerator.mixins=false} and {@code -Dnativeaccelerator.mixins.disable=SimpleBitStorageMixin}.</p>
 *
 * <p>Package choice: {@code SimpleBitStorage} is a common class present on both the client and a dedicated
 * server, so this Mixin belongs in the ungated common package rather than {@code ...mixin.client} or
 * {@code ...mixin.server}. That keeps chunk and light decoding accelerated on servers too.</p>
 */
@Mixin(SimpleBitStorage.class)
public abstract class SimpleBitStorageMixin {

    @Shadow @Final private long[] data;
    @Shadow @Final private int bits;
    @Shadow @Final private int size;

    @Inject(method = "unpack([I)V", at = @At("HEAD"), cancellable = true)
    private void nativeaccelerator$unpack(int[] output, CallbackInfo ci) {
        if (BitStorageAcceleration.unpackSimple(this.data, this.bits, this.size, output)) {
            ci.cancel();
        }
    }
}
