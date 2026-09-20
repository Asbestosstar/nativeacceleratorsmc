package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.cache.PersistentAtlasLayoutCache;
import com.asbestosstar.nativeaccelerator.client.AtlasWorkScheduler;
import com.asbestosstar.nativeaccelerator.client.CachedTextureAtlasSprite;
import com.asbestosstar.nativeaccelerator.client.FastStitcher;
import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import com.asbestosstar.nativeaccelerator.client.StitcherParityVerifier;
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
import org.spongepowered.asm.mixin.Unique;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * High-throughput SpriteLoader.stitch replacement with a correctness fence around atlas placement.
 *
 * <p>The expensive work (sprite decoding and mip generation) remains parallel. FastStitcher remains the
 * primary packer, but a cold layout is structurally checked and, by default, compared once against
 * Minecraft's Stitcher before the layout is allowed into the persistent cache. Warm reloads then reuse
 * only that verified layout, keeping the normal fast path fast while preventing a stale/bad placement
 * from surviving across launches.</p>
 */
@Mixin(SpriteLoader.class)
public abstract class SpriteLoaderParallelMipmapMixin {
    @Unique private static final AtomicBoolean NATIVEACCELERATOR_FIX28_ATLAS_MARKER = new AtomicBoolean();
    private static final Logger NATIVEACCELERATOR_LOGGER = LogUtils.getLogger();
    private static final Identifier NATIVEACCELERATOR_GUI_ATLAS =
            Identifier.withDefaultNamespace("textures/atlas/gui.png");
    private static final boolean NATIVEACCELERATOR_FAST_STITCHER =
            NativeAcceleratorConfig.booleanValue("atlas.fastStitcher", true);
    private static final boolean NATIVEACCELERATOR_SAFE_FAST_STITCHER =
            NativeAcceleratorConfig.booleanValue("atlas.safeFastStitcher", true);
    private static final boolean NATIVEACCELERATOR_PARALLEL_MIPS =
            NativeAcceleratorConfig.booleanValue("atlas.parallelMipmaps", true);
    private static final boolean NATIVEACCELERATOR_VERIFY_FAST_STITCHER =
            NativeAcceleratorConfig.booleanValue("atlas.verifyFastStitcher", false);
    private static final boolean NATIVEACCELERATOR_LAYOUT_REPLAY =
            NativeAcceleratorConfig.booleanValue("cache.atlasLayout.replay", false);

    @Shadow @Final private Identifier location;
    @Shadow @Final private int maxSupportedTextureSize;

    @Invoker("getStitchedSprites")
    protected abstract Map<Identifier, TextureAtlasSprite> nativeaccelerator$invokeGetStitchedSprites(
            Stitcher<SpriteContents> stitcher, int atlasWidth, int atlasHeight);

    @Inject(method = "stitch", at = @At("HEAD"), cancellable = true, require = 0)
    private void nativeaccelerator$parallelMipmaps(List<SpriteContents> sprites, int maxMipmapLevels,
            Executor executor, CallbackInfoReturnable<SpriteLoader.Preparations> cir) {
        if (NATIVEACCELERATOR_FIX28_ATLAS_MARKER.compareAndSet(false, true)) {
            System.out.println("[Native Accelerator] Atlas marker: optimized-restored-fix28");
        }
        if (!NATIVEACCELERATOR_FAST_STITCHER && !NATIVEACCELERATOR_PARALLEL_MIPS) return;

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
            boolean guiAtlas = NATIVEACCELERATOR_GUI_ATLAS.equals(this.location);
            int padding = 1 << mipLevel << Mth.clamp(anisotropyBit - 1, 0, 4);

            // Placement replay is deliberately off by default. The expensive PNG decode cache and
            // parallel mip path remain active, while placement is recomputed from the current live sprite set.
            // Also, fastStitcher=false must be a real vanilla diagnostic: never replay a cached layout then.
            boolean allowLayoutReplay = NATIVEACCELERATOR_LAYOUT_REPLAY
                    && NATIVEACCELERATOR_FAST_STITCHER && !guiAtlas;
            PersistentAtlasLayoutCache.Layout cachedLayout = allowLayoutReplay
                    ? PersistentAtlasLayoutCache.load(this.location, sprites, maxTextureSize, mipLevel, anisotropyBit)
                    : null;
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
                    ModelPipelineProfiler.addCount("atlas.stitch.verified-layout-replay", 1);
                    NATIVEACCELERATOR_LOGGER.debug("Replaying verified atlas layout for {}", this.location);
                } else {
                    result = null;
                }
            } else {
                width = 0;
                height = 0;
                result = null;
            }

            if (result == null) {
                Stitcher<SpriteContents> stitcher = NATIVEACCELERATOR_FAST_STITCHER
                        ? new FastStitcher<>(maxTextureSize, maxTextureSize, mipLevel, anisotropyBit)
                        : new Stitcher<>(maxTextureSize, maxTextureSize, mipLevel, anisotropyBit);
                for (SpriteContents spriteInfo : sprites) stitcher.registerSprite(spriteInfo);
                boolean placementVerified = !NATIVEACCELERATOR_FAST_STITCHER;

                try {
                    long stitchStarted = ModelPipelineProfiler.start();
                    stitcher.stitch();
                    ModelPipelineProfiler.end(NATIVEACCELERATOR_FAST_STITCHER
                            ? "atlas.fast-stitcher.placement" : "atlas.stitch.placement", stitchStarted);
                    if (NATIVEACCELERATOR_FAST_STITCHER) {
                        ModelPipelineProfiler.addCount("atlas.fast-stitcher.sprites", sprites.size());

                        String structuralError = StitcherParityVerifier.validationError(stitcher, mipLevel);
                        if (structuralError != null) {
                            NATIVEACCELERATOR_LOGGER.warn(
                                    "FastStitcher produced an invalid layout for {} ({}); falling back to vanilla",
                                    this.location, structuralError);
                            ModelPipelineProfiler.addCount("atlas.fast-stitcher.validation-failure", 1);
                            stitcher = nativeaccelerator$vanillaStitcher(
                                    sprites, maxTextureSize, mipLevel, anisotropyBit);
                            placementVerified = true;
                        } else if (NATIVEACCELERATOR_SAFE_FAST_STITCHER || NATIVEACCELERATOR_VERIFY_FAST_STITCHER) {
                            long verifyStarted = ModelPipelineProfiler.start();
                            Stitcher<SpriteContents> vanilla = nativeaccelerator$vanillaStitcher(
                                    sprites, maxTextureSize, mipLevel, anisotropyBit);
                            String mismatch = StitcherParityVerifier.mismatch(stitcher, vanilla, mipLevel);
                            ModelPipelineProfiler.end("atlas.fast-stitcher.parity", verifyStarted);
                            if (mismatch == null) {
                                placementVerified = true;
                                ModelPipelineProfiler.addCount("atlas.fast-stitcher.parity-match", 1);
                            } else {
                                NATIVEACCELERATOR_LOGGER.warn(
                                        "FastStitcher parity mismatch for {} ({}); using vanilla placement",
                                        this.location, mismatch);
                                ModelPipelineProfiler.addCount("atlas.fast-stitcher.parity-mismatch", 1);
                                stitcher = vanilla;
                                placementVerified = true;
                            }
                        }
                    }
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
                if (!guiAtlas) {
                    // Keep recording verified layouts as a cheap persistent diagnostic/hint even when
                    // replay is disabled. This preserves cache observability without trusting old placement.
                    PersistentAtlasLayoutCache.store(this.location, sprites, maxTextureSize, mipLevel, anisotropyBit,
                            width, height, padding, placementVerified, result);
                }
            }

            TextureAtlasSprite missingSprite = result.get(MissingTextureAtlasSprite.getLocation());
            CompletableFuture<Void> readyForUpload;
            if (NATIVEACCELERATOR_PARALLEL_MIPS) {
                ArrayList<TextureAtlasSprite> mipTargets = new ArrayList<>(result.values());
                readyForUpload = AtlasWorkScheduler.runMipmaps(
                        mipTargets, mipLevel, executor, "atlas.mipmap.dynamic");
            } else {
                Map<Identifier, TextureAtlasSprite> finalResult = result;
                readyForUpload = CompletableFuture.runAsync(
                        () -> finalResult.values().forEach(sprite -> sprite.contents().increaseMipLevel(mipLevel)),
                        executor);
            }

            ModelPipelineProfiler.end("atlas.stitch.setup", totalStarted);
            cir.setReturnValue(new SpriteLoader.Preparations(width, height, mipLevel, missingSprite, result, readyForUpload));
        }
    }

    private static Stitcher<SpriteContents> nativeaccelerator$vanillaStitcher(
            List<SpriteContents> sprites, int maxTextureSize, int mipLevel, int anisotropyBit) {
        Stitcher<SpriteContents> vanilla = new Stitcher<>(maxTextureSize, maxTextureSize, mipLevel, anisotropyBit);
        for (SpriteContents spriteInfo : sprites) vanilla.registerSprite(spriteInfo);
        vanilla.stitch();
        return vanilla;
    }
}
