package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.client.CachedSpriteResourceLoader;
import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/** Routes atlas PNG loads through the persistent decoded-texture cache. */
@Mixin(SpriteLoader.class)
public abstract class SpriteResourceLoaderCacheMixin {
    private static final boolean NATIVEACCELERATOR_CACHE_TEXTURES =
            NativeAcceleratorConfig.booleanValue("cache.decodedTextures", true);

    @Redirect(method = "loadAndStitch",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/atlas/SpriteResourceLoader;create(Ljava/util/Set;)Lnet/minecraft/client/renderer/texture/atlas/SpriteResourceLoader;"),
            require = 0)
    private SpriteResourceLoader nativeaccelerator$cachedSpriteLoader(Set<MetadataSectionType<?>> metadataTypes) {
        return NATIVEACCELERATOR_CACHE_TEXTURES
                ? CachedSpriteResourceLoader.create(metadataTypes)
                : SpriteResourceLoader.create(metadataTypes);
    }
}
