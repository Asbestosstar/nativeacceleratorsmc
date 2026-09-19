package com.asbestosstar.nativeaccelerator.client;

import com.asbestosstar.nativeaccelerator.cache.PersistentResourceCache;
import com.asbestosstar.nativeaccelerator.cache.PersistentTextureCache;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** SpriteResourceLoader equivalent with persistent decoded-RGBA reuse. */
public final class CachedSpriteResourceLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CachedSpriteResourceLoader() {}

    public static SpriteResourceLoader create(Set<MetadataSectionType<?>> additionalMetadataSections) {
        return (spriteLocation, resource) -> load(spriteLocation, resource, additionalMetadataSections);
    }

    private static SpriteContents load(Identifier spriteLocation, Resource resource,
            Set<MetadataSectionType<?>> additionalMetadataSections) {
        long totalStarted = ModelPipelineProfiler.start();
        FrameSize frameSize;
        NativeImage image;
        List<MetadataSectionType.WithValue<?>> additionalMetadata;
        Optional<TextureMetadataSection> textureInfo;
        Optional<AnimationMetadataSection> animationInfo;
        try {
            long metadataStarted = ModelPipelineProfiler.start();
            ResourceMetadata metadata = resource.metadata();
            animationInfo = metadata.getSection(AnimationMetadataSection.TYPE);
            textureInfo = metadata.getSection(TextureMetadataSection.TYPE);
            additionalMetadata = metadata.getTypedSections(additionalMetadataSections);
            ModelPipelineProfiler.end("texture.metadata", metadataStarted);
        } catch (Exception exception) {
            LOGGER.error("Unable to parse metadata from {}", spriteLocation, exception);
            return null;
        }

        image = PersistentTextureCache.load(spriteLocation, resource);
        if (image == null) {
            long decodeStarted = ModelPipelineProfiler.start();
            try (InputStream input = PersistentResourceCache.open("textures", spriteLocation, resource)) {
                image = NativeImage.read(input);
            } catch (IOException exception) {
                LOGGER.error("Using missing texture, unable to load {}", spriteLocation, exception);
                return null;
            }
            ModelPipelineProfiler.end("texture.png-stb-decode", decodeStarted);
            ModelPipelineProfiler.addCount("texture.png-stb-decode-count", 1);
            PersistentTextureCache.store(spriteLocation, resource, image);
        }

        if (animationInfo.isPresent()) {
            frameSize = animationInfo.get().calculateFrameSize(image.getWidth(), image.getHeight());
            if (!Mth.isMultipleOf(image.getWidth(), frameSize.width())
                    || !Mth.isMultipleOf(image.getHeight(), frameSize.height())) {
                LOGGER.error("Image {} size {},{} is not multiple of frame size {},{}",
                        spriteLocation, image.getWidth(), image.getHeight(), frameSize.width(), frameSize.height());
                image.close();
                return null;
            }
        } else {
            frameSize = new FrameSize(image.getWidth(), image.getHeight());
        }
        SpriteContents contents = new SpriteContents(spriteLocation, frameSize, image,
                animationInfo, additionalMetadata, textureInfo);
        ModelPipelineProfiler.end("texture.sprite-loader.total", totalStarted);
        return contents;
    }
}

