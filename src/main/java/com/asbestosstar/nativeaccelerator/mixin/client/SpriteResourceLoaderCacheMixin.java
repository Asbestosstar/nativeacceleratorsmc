package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.CachedSpriteResourceLoader;
import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/** Routes atlas PNG loads through the persistent decoded-texture cache. */
@Mixin(SpriteLoader.class)
public abstract class SpriteResourceLoaderCacheMixin {
    private static final Identifier NATIVEACCELERATOR_GUI_ATLAS =
            Identifier.withDefaultNamespace("textures/atlas/gui.png");

    @Shadow @Final private Identifier location;
    private static final boolean NATIVEACCELERATOR_CACHE_TEXTURES =
            NativeAcceleratorConfig.booleanValue("cache.decodedTextures", true);

    @Redirect(method = "loadAndStitch",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/atlas/SpriteResourceLoader;create(Ljava/util/Set;)Lnet/minecraft/client/renderer/texture/atlas/SpriteResourceLoader;"),
            require = 0)
    private SpriteResourceLoader nativeaccelerator$cachedSpriteLoader(Set<MetadataSectionType<?>> metadataTypes) {
        // GUI sprites carry rendering metadata (nine-slice/stretch rules) and are correctness-sensitive.
        // Keep GUI sprite decoding on Minecraft's native path. Live atlas placement may still use
        // FastStitcher, but old persistent GUI pixel-cache entries are never consulted.
        if (!NATIVEACCELERATOR_CACHE_TEXTURES || NATIVEACCELERATOR_GUI_ATLAS.equals(this.location)) {
            return SpriteResourceLoader.create(metadataTypes);
        }
        return CachedSpriteResourceLoader.create(metadataTypes);
    }
}
