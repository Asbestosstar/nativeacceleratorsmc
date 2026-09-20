package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Raised Motif/CDE controls for the Graphics application window. */
@Mixin(AbstractButton.class)
public abstract class VideoSettingsUnixButtonMixin {
    @Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true, require = 0)
    private void nativeaccelerator$unixButtonSprite(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (!(Minecraft.getInstance().gui.screen() instanceof VideoSettingsScreen)) return;
        if (!NativeAcceleratorConfig.booleanValue("graphics.unixTheme", true)) return;

        AbstractButton button = (AbstractButton)(Object)this;
        int x0 = button.getX();
        int y0 = button.getY();
        int x1 = x0 + button.getWidth();
        int y1 = y0 + button.getHeight();
        boolean hot = button.isHoveredOrFocused();
        boolean active = button.active;

        int face = active ? (hot ? 0xFF77737F : 0xFF66626E) : 0xFF53515A;
        int light = active ? 0xFFC8C5CE : 0xFF77747D;
        int mid = active ? 0xFF918D99 : 0xFF65626C;
        int dark = 0xFF35323C;
        int shadow = 0xFF211F27;

        graphics.fill(x0 + 2, y0 + 2, x1 + 2, y1 + 2, shadow);
        graphics.fill(x0, y0, x1, y1, face);
        graphics.fill(x0, y0, x1, y0 + 2, light);
        graphics.fill(x0, y0, x0 + 2, y1, light);
        graphics.fill(x0 + 2, y0 + 2, x1 - 2, y0 + 3, mid);
        graphics.fill(x0, y1 - 2, x1, y1, dark);
        graphics.fill(x1 - 2, y0, x1, y1, dark);

        if (hot && active) {
            // Small Uma-purple focus line, similar to Motif keyboard focus rather than neon framing.
            graphics.fill(x0 + 3, y0 + 3, x1 - 3, y0 + 4, 0xFFD47AB8);
        }
        ci.cancel();
    }
}
