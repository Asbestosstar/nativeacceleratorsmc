package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Recessed CDE work pane with a persistent Uma side illustration. */
@Mixin(AbstractSelectionList.class)
public abstract class VideoSettingsUnixListMixin {
    private static final Identifier NATIVEACCELERATOR_UMA =
            Identifier.fromNamespaceAndPath("nativeaccelerator", "textures/gui/uma.png");
    private static final String NATIVEACCELERATOR_UMA_CLASSPATH =
            "/assets/nativeaccelerator/textures/gui/uma.png";
    private static final AtomicBoolean NATIVEACCELERATOR_UMA_REGISTERED = new AtomicBoolean();
    private static final AtomicBoolean NATIVEACCELERATOR_UMA_FAILURE_REPORTED = new AtomicBoolean();

    @Inject(method = "extractListBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void nativeaccelerator$unixListBackground(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (!(Minecraft.getInstance().gui.screen() instanceof VideoSettingsScreen)) return;
        if (!NativeAcceleratorConfig.booleanValue("graphics.unixTheme", true)) return;

        AbstractSelectionList<?> list = (AbstractSelectionList<?>)(Object)this;
        int x0 = list.getX();
        int y0 = list.getY();
        int x1 = list.getRight();
        int y1 = list.getBottom();

        // Classic recessed Motif work area: grey-violet surface with a sunken 3-D rim.
        graphics.fill(x0, y0, x1, y1, 0xFF4A4752);
        graphics.fill(x0, y0, x1, y0 + 2, 0xFF34313B);
        graphics.fill(x0, y0, x0 + 2, y1, 0xFF34313B);
        graphics.fill(x0, y1 - 2, x1, y1, 0xFFCBC7D0);
        graphics.fill(x1 - 2, y0, x1, y1, 0xFFCBC7D0);
        graphics.fill(x0 + 3, y0 + 3, x1 - 3, y1 - 3, 0xFF696572);

        // Responsive Uma branding pane.  Do not hide it on low resolutions: the whole point of the
        // CDE-style shell is to retain the workstation artwork even when the controls become compact.
        if (NativeAcceleratorConfig.booleanValue("graphics.crt.uma", true)
                && nativeaccelerator$ensureUmaTexture()) {
            int opacity = Math.max(0, Math.min(100,
                    NativeAcceleratorConfig.intValue("graphics.crt.umaOpacity", 100, 0)));
            int scale = Math.max(50, Math.min(160,
                    NativeAcceleratorConfig.intValue("graphics.crt.umaScale", 100, 0)));

            int availableH = Math.max(96, list.getHeight() - 16);
            int availableW = Math.max(96, list.getWidth() - 16);
            boolean compact = list.getWidth() < 650 || list.getHeight() < 360;

            // Wide mode: tall preview pane. Compact mode: still use roughly 60% of the work-area
            // height so Uma remains visually obvious rather than shrinking to an icon.
            int baseH = compact
                    ? Math.max(150, Math.min(320, availableH * 3 / 5))
                    : Math.max(220, Math.min(520, availableH - 8));
            int drawH = Math.max(96, baseH * scale / 100);
            int drawW = drawH * 750 / 900;

            // Never let the art grow beyond the work area. Preserve aspect ratio while fitting.
            int maxW = compact ? Math.max(120, availableW * 2 / 5) : Math.max(180, availableW * 9 / 20);
            if (drawW > maxW) {
                drawW = maxW;
                drawH = drawW * 900 / 750;
            }
            if (drawH > availableH) {
                drawH = availableH;
                drawW = drawH * 750 / 900;
            }

            int x = x1 - drawW - (compact ? 7 : 12);
            int y = compact
                    ? y1 - drawH - 7
                    : y0 + Math.max(6, (list.getHeight() - drawH) / 2);

            int frameLeft = x - (compact ? 4 : 8);
            int frameTop = y - (compact ? 4 : 8);
            int frameRight = Math.min(x1 - 3, x + drawW + (compact ? 4 : 8));
            int frameBottom = Math.min(y1 - 3, y + drawH + (compact ? 4 : 8));

            // Slightly translucent CDE preview well. On compact screens keep it lighter so the art
            // does not disappear into a dark rectangle behind the controls.
            graphics.fill(frameLeft, frameTop, frameRight, frameBottom,
                    ARGB.color(compact ? 150 : 205, 86, 79, 98));
            graphics.fill(frameLeft, frameTop, frameRight, frameTop + 2, 0xFFD8D4DE);
            graphics.fill(frameLeft, frameTop, frameLeft + 2, frameBottom, 0xFFD8D4DE);
            graphics.fill(frameLeft, frameBottom - 2, frameRight, frameBottom, 0xFF35313D);
            graphics.fill(frameRight - 2, frameTop, frameRight, frameBottom, 0xFF35313D);

            graphics.blit(RenderPipelines.GUI_TEXTURED, NATIVEACCELERATOR_UMA,
                    x, y, 0.0f, 0.0f, drawW, drawH,
                    750, 900, 750, 900, ARGB.white(opacity / 100.0f));
        }
        ci.cancel();
    }

    /**
     * Load Uma directly from this mod JAR and register it as a DynamicTexture.
     * This deliberately bypasses ResourceManager namespace discovery because some universal-loader
     * combinations have exposed the Java classes while failing to merge the mod's assets namespace.
     */
    private static boolean nativeaccelerator$ensureUmaTexture() {
        if (NATIVEACCELERATOR_UMA_REGISTERED.get()) return true;
        synchronized (NATIVEACCELERATOR_UMA_REGISTERED) {
            if (NATIVEACCELERATOR_UMA_REGISTERED.get()) return true;
            try (InputStream input = VideoSettingsUnixListMixin.class.getResourceAsStream(NATIVEACCELERATOR_UMA_CLASSPATH)) {
                if (input == null) {
                    if (NATIVEACCELERATOR_UMA_FAILURE_REPORTED.compareAndSet(false, true)) {
                        System.err.println("[Native Accelerator] Uma texture missing from mod JAR at "
                                + NATIVEACCELERATOR_UMA_CLASSPATH);
                    }
                    return false;
                }
                NativeImage image = NativeImage.read(input);
                if (image.getWidth() != 750 || image.getHeight() != 900) {
                    System.out.println("[Native Accelerator] Uma texture dimensions="
                            + image.getWidth() + "x" + image.getHeight() + " (expected 750x900; rendering anyway)");
                }
                DynamicTexture texture = new DynamicTexture(() -> "Native Accelerator Uma", image);
                Minecraft.getInstance().getTextureManager().register(NATIVEACCELERATOR_UMA, texture);
                NATIVEACCELERATOR_UMA_REGISTERED.set(true);
                System.out.println("[Native Accelerator] Uma texture loaded directly from mod JAR and registered: "
                        + NATIVEACCELERATOR_UMA);
                return true;
            } catch (Throwable error) {
                if (NATIVEACCELERATOR_UMA_FAILURE_REPORTED.compareAndSet(false, true)) {
                    System.err.println("[Native Accelerator] Failed to load Uma texture directly from mod JAR: " + error);
                    error.printStackTrace(System.err);
                }
                return false;
            }
        }
    }

    @Inject(method = "extractListSeparators", at = @At("HEAD"), cancellable = true, require = 0)
    private void nativeaccelerator$unixListSeparators(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (!(Minecraft.getInstance().gui.screen() instanceof VideoSettingsScreen)) return;
        if (!NativeAcceleratorConfig.booleanValue("graphics.unixTheme", true)) return;
        AbstractSelectionList<?> list = (AbstractSelectionList<?>)(Object)this;
        graphics.fill(list.getX(), list.getY() - 3, list.getRight(), list.getY() - 1, 0xFFC9C5D0);
        graphics.fill(list.getX(), list.getY() - 1, list.getRight(), list.getY(), 0xFF403C48);
        graphics.fill(list.getX(), list.getBottom(), list.getRight(), list.getBottom() + 1, 0xFFC9C5D0);
        graphics.fill(list.getX(), list.getBottom() + 1, list.getRight(), list.getBottom() + 3, 0xFF403C48);
        ci.cancel();
    }
}

