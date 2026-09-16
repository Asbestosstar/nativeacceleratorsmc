package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.cache.PersistentAtlasLayoutCache;
import com.asbestosstar.nativeaccelerator.client.CachedTextureAtlasSprite;
import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import com.asbestosstar.nativeaccelerator.client.ModelWorkScheduler;
import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.StitcherException;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.Zone;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Equivalent SpriteLoader.stitch path with mipmap generation split over the shared work-stealing pool.
 * The stitch placement algorithm itself remains vanilla.
 */
@Mixin(SpriteLoader.class)
public abstract class SpriteLoaderParallelMipmapMixin {
    private static final Logger NATIVEACCELERATOR_LOGGER = LogUtils.getLogger();
    private static final boolean NATIVEACCELERATOR_PARALLEL_MIPS =
            NativeAcceleratorConfig.booleanValue("atlas.parallelMipmaps", true);

    @Shadow @Final private Identifier location;
    @Shadow @Final private int maxSupportedTextureSize;
    @Invoker("getStitchedSprites")
    protected abstract Map<Identifier, TextureAtlasSprite> nativeaccelerator$invokeGetStitchedSprites(
            Stitcher<SpriteContents> stitcher, int atlasWidth, int atlasHeight);

    @Inject(method = "stitch", at = @At("HEAD"), cancellable = true, require = 0)
    private void nativeaccelerator$parallelMipmaps(List<SpriteContents> sprites, int maxMipmapLevels,
            Executor executor, CallbackInfoReturnable<SpriteLoader.Preparations> cir) {
        if (!NATIVEACCELERATOR_PARALLEL_MIPS) return;
        long totalStarted = ModelPipelineProfiler.start();
        try (Zone ignored = Profiler.get().zone(() -> "stitch " + this.location)) {
            int maxTextureSize = this.maxSupportedTextureSize;
            int minTexelSize = Integer.MAX_VALUE;
            int lowestOneBit = 1 << maxMipmapLevels;
            for (SpriteContents spriteInfo : sprites) {
                minTexelSize = Math.min(minTexelSize, Math.min(spriteInfo.width(), spriteInfo.height()));
                int lowestTextureBit = Math.min(Integer.lowestOneBit(spriteInfo.width()),
                        Integer.lowestOneBit(spriteInfo.height()));
                if (lowestTextureBit >= lowestOneBit) continue;
                NATIVEACCELERATOR_LOGGER.warn("Texture {} with size {}x{} limits mip level from {} to {}",
                        spriteInfo.name(), spriteInfo.width(), spriteInfo.height(),
                        Mth.log2(lowestOneBit), Mth.log2(lowestTextureBit));
                lowestOneBit = lowestTextureBit;
            }
            int minSize = Math.min(minTexelSize, lowestOneBit);
            int minPowerOfTwo = Mth.log2(minSize);
            int mipLevel;
            if (minPowerOfTwo < maxMipmapLevels) {
                NATIVEACCELERATOR_LOGGER.warn("{}: dropping miplevel from {} to {}, because of minimum power of two: {}",
                        this.location, maxMipmapLevels, minPowerOfTwo, minSize);
                mipLevel = minPowerOfTwo;
            } else {
                mipLevel = maxMipmapLevels;
            }

            Options options = Minecraft.getInstance().options;
            int anisotropyBit = options.textureFiltering().get() != TextureFilteringMethod.ANISOTROPIC
                    ? 0 : options.maxAnisotropyBit().get();
            PersistentAtlasLayoutCache.Layout cachedLayout = PersistentAtlasLayoutCache.load(
                    this.location, sprites, maxTextureSize, mipLevel, anisotropyBit);
            int padding = 1 << mipLevel << Mth.clamp(anisotropyBit - 1, 0, 4);
            int width;
            int height;
            Map<Identifier, TextureAtlasSprite> result;
            if (cachedLayout != null) {
                width = cachedLayout.width();
                height = cachedLayout.height();
                HashMap<Identifier, TextureAtlasSprite> cachedSprites = new HashMap<>(
                        Math.max(16, sprites.size() * 4 / 3 + 1));
                for (SpriteContents contents : sprites) {
                    PersistentAtlasLayoutCache.Position position = cachedLayout.positions().get(contents.name());
                    if (position == null) {
                        cachedSprites.clear();
                        break;
                    }
                    cachedSprites.put(contents.name(), new CachedTextureAtlasSprite(this.location, contents,
                            width, height, position.x(), position.y(), cachedLayout.padding()));
                }
                if (cachedSprites.size() == sprites.size()) {
                    result = cachedSprites;
                    ModelPipelineProfiler.addCount("atlas.stitch.layout-skipped", 1);
                } else {
                    result = null;
                }
            } else {
                width = 0;
                height = 0;
                result = null;
            }

            if (result == null) {
                Stitcher<SpriteContents> stitcher = new Stitcher<>(maxTextureSize, maxTextureSize, mipLevel, anisotropyBit);
                for (SpriteContents spriteInfo : sprites) stitcher.registerSprite(spriteInfo);
                try {
                    long stitchStarted = ModelPipelineProfiler.start();
                    stitcher.stitch();
                    ModelPipelineProfiler.end("atlas.stitch.placement", stitchStarted);
                } catch (StitcherException exception) {
                    CrashReport report = CrashReport.forThrowable(exception, "Stitching");
                    CrashReportCategory category = report.addCategory("Stitcher");
                    category.setDetail("Sprites", exception.getAllSprites().stream()
                            .map(s -> String.format(Locale.ROOT, "%s[%dx%d]", s.name(), s.width(), s.height()))
                            .collect(Collectors.joining(",")));
                    category.setDetail("Max Texture Size", maxTextureSize);
                    throw new ReportedException(report);
                }
                width = stitcher.getWidth();
                height = stitcher.getHeight();
                result = this.nativeaccelerator$invokeGetStitchedSprites(stitcher, width, height);
                PersistentAtlasLayoutCache.store(this.location, sprites, maxTextureSize, mipLevel, anisotropyBit,
                        width, height, padding, result);
            }
            TextureAtlasSprite missingSprite = result.get(MissingTextureAtlasSprite.getLocation());
            ArrayList<TextureAtlasSprite> mipTargets = new ArrayList<>(result.values());
            CompletableFuture<Void> readyForUpload = ModelWorkScheduler.runIndexed(
                    mipTargets.size(), executor,
                    i -> mipTargets.get(i).contents().increaseMipLevel(mipLevel),
                    "atlas.mipmap.dynamic");
            ModelPipelineProfiler.end("atlas.stitch.setup", totalStarted);
            cir.setReturnValue(new SpriteLoader.Preparations(width, height, mipLevel, missingSprite, result, readyForUpload));
        }
    }
}
