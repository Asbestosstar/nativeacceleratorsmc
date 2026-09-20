package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.util.ARGB;
import java.util.concurrent.atomic.AtomicBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Late-1990s CDE/Motif-style application shell for the Graphics control panel. */
@Mixin(Screen.class)
public abstract class VideoSettingsUnixThemeMixin {
    private static final AtomicBoolean NATIVEACCELERATOR_THEME_MARKER = new AtomicBoolean();

    @Inject(method = "extractBackground", at = @At("TAIL"), require = 0)
    private void nativeaccelerator$unixGraphicsBackground(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!((Object)this instanceof VideoSettingsScreen screen)) return;
        if (!NativeAcceleratorConfig.booleanValue("graphics.unixTheme", true)) return;
        if (NATIVEACCELERATOR_THEME_MARKER.compareAndSet(false, true)) {
            System.out.println("[Native Accelerator] CDE/Motif Graphics shell active; category menu + floating control panel");
        }

        int width = screen.width;
        int height = screen.height;

        // Desktop: subdued late-90s workstation purple, chosen from Uma's violet range.
        graphics.fill(0, 0, width, height, 0xFF5D5688);
        if (NativeAcceleratorConfig.booleanValue("graphics.crt.scanlines", true)) {
            for (int y = 0; y < height; y += 4) {
                graphics.fill(0, y, width, y + 1, ARGB.color(14, 0, 0, 0));
            }
        }

        // Floating CDE/Motif application window.  The screen is intentionally no longer a full-screen
        // Minecraft panel: it reads as a workstation control utility sitting on a desktop.
        int marginX = Math.max(12, Math.min(34, width / 22));
        int marginY = Math.max(12, Math.min(26, height / 18));
        int x0 = marginX;
        int y0 = marginY;
        int x1 = width - marginX;
        int y1 = height - marginY;

        // Window shadow + raised outer bevel.
        graphics.fill(x0 + 5, y0 + 5, x1 + 5, y1 + 5, ARGB.color(120, 20, 17, 31));
        graphics.fill(x0, y0, x1, y1, 0xFFA7A5B0);
        graphics.fill(x0, y0, x1, y0 + 2, 0xFFE1DFE6);
        graphics.fill(x0, y0, x0 + 2, y1, 0xFFE1DFE6);
        graphics.fill(x0, y1 - 2, x1, y1, 0xFF4B4854);
        graphics.fill(x1 - 2, y0, x1, y1, 0xFF4B4854);

        // Title bar inspired by CDE dtwm: muted plum/rose rather than Minecraft green.
        graphics.fill(x0 + 4, y0 + 4, x1 - 4, y0 + 25, 0xFF76556F);
        graphics.fill(x0 + 5, y0 + 5, x1 - 5, y0 + 7, 0xFFB98AAE);
        graphics.fill(x0 + 5, y0 + 23, x1 - 5, y0 + 25, 0xFF463443);

        // Menu bar and status/footer ridges.  Actual menu buttons are provided by the VideoSettings mixin.
        graphics.fill(x0 + 4, y0 + 27, x1 - 4, y0 + 51, 0xFF9B99A4);
        graphics.fill(x0 + 4, y0 + 27, x1 - 4, y0 + 29, 0xFFD4D2D9);
        graphics.fill(x0 + 4, y0 + 49, x1 - 4, y0 + 51, 0xFF514E59);

        graphics.fill(x0 + 4, y1 - 28, x1 - 4, y1 - 4, 0xFF92909B);
        graphics.fill(x0 + 4, y1 - 28, x1 - 4, y1 - 26, 0xFFD4D2D9);
        graphics.fill(x0 + 4, y1 - 6, x1 - 4, y1 - 4, 0xFF514E59);
    }
}
