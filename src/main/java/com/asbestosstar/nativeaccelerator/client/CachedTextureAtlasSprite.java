package com.asbestosstar.nativeaccelerator.client;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/** Public construction seam for a validated persistent atlas placement. */
public final class CachedTextureAtlasSprite extends TextureAtlasSprite {
    public CachedTextureAtlasSprite(Identifier atlasLocation, SpriteContents contents,
            int atlasWidth, int atlasHeight, int x, int y, int padding) {
        super(atlasLocation, contents, atlasWidth, atlasHeight, x, y, padding);
    }
}
