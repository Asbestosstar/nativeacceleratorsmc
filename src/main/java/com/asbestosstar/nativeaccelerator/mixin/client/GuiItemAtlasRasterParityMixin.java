package com.asbestosstar.nativeaccelerator.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.render.GuiItemAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps GUI item-atlas geometry infinitesimally inside the exact slot boundary on the native
 * OpenGL and Vulkan backends.
 *
 * <p>GuiItemAtlas centers an item in a slot and then scales by exactly {@code slotTextureSize}.
 * For physical/block models that can put projected vertices exactly on the slot boundary.  OpenGL
 * and Vulkan then apply their API edge-inclusion rules at precisely the same boundary as the atlas
 * scissor.  Some drivers expose a one-sample triangular/corner hole on cube-like items.  Metal's
 * target-coordinate path does not show the hole.</p>
 *
 * <p>The correction uses {@link Math#nextDown(float)} rather than an arbitrary pixel shrink.  It is
 * therefore visually size-neutral: the scale changes by one floating-point ULP only, but vertices
 * which were mathematically on the clipping edge become strictly interior.  Metal is intentionally
 * left untouched because its fixed item-atlas path is already correct.</p>
 *
 * <p>Disable for A/B testing with
 * {@code -Dnativeaccelerator.renderer.itemAtlas.edgeGuard=false}.</p>
 */
@Mixin(GuiItemAtlas.class)
public abstract class GuiItemAtlasRasterParityMixin {
    private static final boolean NATIVEACCELERATOR_EDGE_GUARD = Boolean.parseBoolean(
            System.getProperty("nativeaccelerator.renderer.itemAtlas.edgeGuard", "true"));
    private static final AtomicBoolean NATIVEACCELERATOR_REPORTED = new AtomicBoolean();

    @ModifyArgs(
            method = "drawToSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"),
            require = 0)
    private void nativeaccelerator$keepNativeAtlasGeometryInsideSlot(Args args) {
        if (!NATIVEACCELERATOR_EDGE_GUARD || !nativeaccelerator$isOpenGlOrVulkan()) {
            return;
        }

        float x = args.get(0);
        float y = args.get(1);
        float z = args.get(2);

        float xMagnitude = Math.abs(x);
        float yMagnitude = Math.abs(y);
        float zMagnitude = Math.abs(z);

        if (xMagnitude > 0.0f) xMagnitude = Math.nextDown(xMagnitude);
        if (yMagnitude > 0.0f) yMagnitude = Math.nextDown(yMagnitude);
        if (zMagnitude > 0.0f) zMagnitude = Math.nextDown(zMagnitude);

        args.set(0, Math.copySign(xMagnitude, x));
        args.set(1, Math.copySign(yMagnitude, y));
        args.set(2, Math.copySign(zMagnitude, z));

        if (NATIVEACCELERATOR_REPORTED.compareAndSet(false, true)) {
            System.out.println("[Native Accelerator] GUI item-atlas raster edge guard active for "
                    + RenderSystem.getDevice().getDeviceInfo().backendName());
        }
    }

    private static boolean nativeaccelerator$isOpenGlOrVulkan() {
        String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
        if (backend == null) return false;
        String normalized = backend.toLowerCase(Locale.ROOT);
        return normalized.contains("opengl") || normalized.contains("vulkan");
    }
}
