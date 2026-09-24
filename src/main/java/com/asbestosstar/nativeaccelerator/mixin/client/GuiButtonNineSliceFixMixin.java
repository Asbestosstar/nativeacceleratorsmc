package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes the visible repeat seam produced by vanilla's 200x20 tiled nine-slice button when a button is
 * wider than the source sprite (for example CreateWorldScreen.MoreTab's 210px buttons).
 *
 * <p>The vanilla button metadata is a 200x20 nine-slice with a three-pixel border and a tiled inner area.
 * A 210px destination therefore emits one complete 194px inner tile plus a 10px remainder, leaving a very
 * noticeable vertical restart 13px before the right edge.  This mixin changes only that exact vanilla-style
 * metadata signature and only when the destination exceeds the source dimensions.  Borders remain pixel
 * exact; the center/edges are stretched once instead of repeated.</p>
 *
 * <p>Resource packs that change the nine-slice dimensions/border/stretch policy are left alone.  The fix can
 * also be disabled globally with {@code -Dnativeaccelerator.gui.fixButtonNineSliceSeam=false}.</p>
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiButtonNineSliceFixMixin {
    private static final boolean NATIVEACCELERATOR_FIX_BUTTON_SEAM =
            NativeAcceleratorConfig.booleanValue("gui.fixButtonNineSliceSeam", true);

    private static final Identifier NATIVEACCELERATOR_BUTTON =
            Identifier.withDefaultNamespace("widget/button");
    private static final Identifier NATIVEACCELERATOR_BUTTON_DISABLED =
            Identifier.withDefaultNamespace("widget/button_disabled");
    private static final Identifier NATIVEACCELERATOR_BUTTON_HIGHLIGHTED =
            Identifier.withDefaultNamespace("widget/button_highlighted");

    @Shadow @Final private TextureAtlas guiSprites;

    @Invoker("innerBlit")
    protected abstract void nativeaccelerator$invokeInnerBlit(
            RenderPipeline pipeline, Identifier location,
            int x0, int x1, int y0, int y1,
            float u0, float u1, float v0, float v1, int color);

    @Inject(
            method = "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void nativeaccelerator$stretchVanillaButtonCenter(
            RenderPipeline pipeline, Identifier location,
            int x, int y, int width, int height, int color,
            CallbackInfo ci) {
        if (!NATIVEACCELERATOR_FIX_BUTTON_SEAM || width <= 0 || height <= 0
                || !nativeaccelerator$isButtonSprite(location)) {
            return;
        }

        TextureAtlasSprite sprite = this.guiSprites.getSprite(location);
        GuiSpriteScaling scaling = sprite.contents()
                .getAdditionalMetadata(GuiMetadataSection.TYPE)
                .orElse(GuiMetadataSection.DEFAULT)
                .scaling();
        if (!(scaling instanceof GuiSpriteScaling.NineSlice nineSlice)
                || nineSlice.stretchInner()
                || width <= nineSlice.width() && height <= nineSlice.height()
                || !nativeaccelerator$isVanillaButtonNineSlice(nineSlice)) {
            return;
        }

        nativeaccelerator$blitStretchedNineSlice(pipeline, sprite, nineSlice, x, y, width, height, color);
        ci.cancel();
    }

    private static boolean nativeaccelerator$isButtonSprite(Identifier id) {
        return NATIVEACCELERATOR_BUTTON.equals(id)
                || NATIVEACCELERATOR_BUTTON_DISABLED.equals(id)
                || NATIVEACCELERATOR_BUTTON_HIGHLIGHTED.equals(id);
    }

    private static boolean nativeaccelerator$isVanillaButtonNineSlice(GuiSpriteScaling.NineSlice nineSlice) {
        GuiSpriteScaling.NineSlice.Border border = nineSlice.border();
        return nineSlice.width() == 200 && nineSlice.height() == 20
                && border.left() == 3 && border.top() == 3
                && border.right() == 3 && border.bottom() == 3;
    }

    private void nativeaccelerator$blitStretchedNineSlice(
            RenderPipeline pipeline, TextureAtlasSprite sprite, GuiSpriteScaling.NineSlice nineSlice,
            int x, int y, int width, int height, int color) {
        GuiSpriteScaling.NineSlice.Border border = nineSlice.border();
        int left = Math.min(border.left(), width / 2);
        int right = Math.min(border.right(), width / 2);
        int top = Math.min(border.top(), height / 2);
        int bottom = Math.min(border.bottom(), height / 2);

        int sourceWidth = nineSlice.width();
        int sourceHeight = nineSlice.height();
        int sourceCenterWidth = sourceWidth - left - right;
        int sourceCenterHeight = sourceHeight - top - bottom;
        int centerWidth = width - left - right;
        int centerHeight = height - top - bottom;

        // Top row.
        nativeaccelerator$blitRegion(pipeline, sprite, sourceWidth, sourceHeight,
                0, 0, left, top, x, y, left, top, color);
        nativeaccelerator$blitRegion(pipeline, sprite, sourceWidth, sourceHeight,
                left, 0, sourceCenterWidth, top, x + left, y, centerWidth, top, color);
        nativeaccelerator$blitRegion(pipeline, sprite, sourceWidth, sourceHeight,
                sourceWidth - right, 0, right, top, x + width - right, y, right, top, color);

        // Middle row.
        nativeaccelerator$blitRegion(pipeline, sprite, sourceWidth, sourceHeight,
                0, top, left, sourceCenterHeight, x, y + top, left, centerHeight, color);
        nativeaccelerator$blitRegion(pipeline, sprite, sourceWidth, sourceHeight,
                left, top, sourceCenterWidth, sourceCenterHeight,
                x + left, y + top, centerWidth, centerHeight, color);
        nativeaccelerator$blitRegion(pipeline, sprite, sourceWidth, sourceHeight,
                sourceWidth - right, top, right, sourceCenterHeight,
                x + width - right, y + top, right, centerHeight, color);

        // Bottom row.
        nativeaccelerator$blitRegion(pipeline, sprite, sourceWidth, sourceHeight,
                0, sourceHeight - bottom, left, bottom,
                x, y + height - bottom, left, bottom, color);
        nativeaccelerator$blitRegion(pipeline, sprite, sourceWidth, sourceHeight,
                left, sourceHeight - bottom, sourceCenterWidth, bottom,
                x + left, y + height - bottom, centerWidth, bottom, color);
        nativeaccelerator$blitRegion(pipeline, sprite, sourceWidth, sourceHeight,
                sourceWidth - right, sourceHeight - bottom, right, bottom,
                x + width - right, y + height - bottom, right, bottom, color);
    }

    private void nativeaccelerator$blitRegion(
            RenderPipeline pipeline, TextureAtlasSprite sprite,
            int sourceWidth, int sourceHeight,
            int sourceX, int sourceY, int sourceRegionWidth, int sourceRegionHeight,
            int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0 || sourceRegionWidth <= 0 || sourceRegionHeight <= 0) return;
        this.nativeaccelerator$invokeInnerBlit(
                pipeline, sprite.atlasLocation(),
                x, x + width, y, y + height,
                sprite.getU((float) sourceX / (float) sourceWidth),
                sprite.getU((float) (sourceX + sourceRegionWidth) / (float) sourceWidth),
                sprite.getV((float) sourceY / (float) sourceHeight),
                sprite.getV((float) (sourceY + sourceRegionHeight) / (float) sourceHeight),
                color);
    }
}

