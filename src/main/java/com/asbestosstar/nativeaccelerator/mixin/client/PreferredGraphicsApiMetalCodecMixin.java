package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.renderer.metal.GraphicsBackendPreference;
import com.mojang.serialization.Codec;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes Minecraft's own preferredGraphicsBackend option accept and persist the fourth value "metal".
 *
 * <p>The Java enum stays untouched. On decode, "metal" maps to DEFAULT only inside Mojang's enum-valued
 * OptionInstance while GraphicsBackendPreference remembers METAL as the authoritative choice. On encode,
 * that authoritative choice is written back, so options.txt really contains:</p>
 *
 * <pre>preferredGraphicsBackend:"metal"</pre>
 */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMetalCodecMixin {
    @Shadow @Final @Mutable
    public static Codec<PreferredGraphicsApi> CODEC;

    @Inject(method = "<clinit>", at = @At("TAIL"), require = 1)
    private static void nativeaccelerator$installMetalOptionCodec(CallbackInfo ci) {
        CODEC = Codec.STRING.xmap(
                GraphicsBackendPreference::decodeMinecraftOption,
                GraphicsBackendPreference::encodeMinecraftOption);
        System.out.println("[Native Accelerator] Minecraft preferredGraphicsBackend codec extended with metal");
    }
}

