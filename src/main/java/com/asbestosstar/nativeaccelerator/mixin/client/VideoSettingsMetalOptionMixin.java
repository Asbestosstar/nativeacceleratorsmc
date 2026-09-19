package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.renderer.metal.MetalGraphicsOption;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Exposes Native Accelerator's renderer selector in Minecraft's Graphics screen.
 *
 * <p>Minecraft 26.3 hard-codes its built-in graphics selector to the three-value
 * {@code PreferredGraphicsApi} enum. Metal therefore cannot be represented by mutating the vanilla
 * option value alone. This mixin deliberately owns a separate four-value renderer option and inserts
 * it directly into the Display section.</p>
 *
 * <p>The {@link #nativeaccelerator$insertRendererSelector(CallbackInfo)} injection is the primary
 * path. It adds a full-width row immediately before Minecraft adds the normal small Display-option
 * grid, so it remains visible even if another mod rewrites or replaces the vanilla Graphics API
 * {@link OptionInstance}. The {@code @ModifyArg} and private-helper return hooks are retained as
 * compatibility polish: when they match, the vanilla three-value Graphics API row is removed rather
 * than showing two competing renderer controls.</p>
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsMetalOptionMixin extends OptionsSubScreen {
    /** Constructor exists only so javac can type-check access to OptionsSubScreen's protected state. */
    protected VideoSettingsMetalOptionMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    /**
     * Guaranteed-visible primary hook. OptionsSubScreen.addContents() creates {@link #list} before
     * invoking addOptions(), so HEAD is early enough to place the selector first in the Display
     * section without depending on any particular vanilla option-list call site.
     */
    @Inject(
            method = "addOptions()V",
            at = @At("HEAD"),
            require = 1)
    private void nativeaccelerator$insertRendererSelector(CallbackInfo ci) {
        OptionsList optionsList = this.list;
        if (optionsList == null) {
            throw new IllegalStateException("VideoSettingsScreen options list was not initialized");
        }
        optionsList.addBig(MetalGraphicsOption.forOptions(this.options));
        MetalGraphicsOption.markInsertedByDisplayOptions(this.options);
        System.out.println("[Native Accelerator] Graphics settings hook active; inserted full-width renderer selector");
    }

    /**
     * Remove the vanilla three-value Graphics API entry when the exact 26.3 Display call shape is
     * still present. The full-width primary selector above does not depend on this cleanup.
     */
    @ModifyArg(
            method = "addOptions()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/OptionsList;addSmall([Lnet/minecraft/client/OptionInstance;)V",
                    ordinal = 0),
            index = 0,
            require = 0)
    private OptionInstance<?>[] nativeaccelerator$replaceDisplayGroup(OptionInstance<?>[] original) {
        OptionInstance<?> vanilla = this.options.preferredGraphicsBackend();
        int matches = 0;
        for (OptionInstance<?> option : original) {
            if (option == vanilla) matches++;
        }
        if (matches == 0) return original;

        OptionInstance<?>[] changed = new OptionInstance<?>[original.length - matches];
        int out = 0;
        for (OptionInstance<?> option : original) {
            if (option != vanilla) changed[out++] = option;
        }
        return changed;
    }

    /** Compatibility fallback for builds/mods that still invoke the private helper directly. */
    @Inject(
            method = "displayOptions(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0)
    private static void nativeaccelerator$replaceGraphicsApiOption(
            Options options,
            CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        OptionInstance<?>[] original = cir.getReturnValue();
        if (original == null || original.length == 0) return;

        OptionInstance<?> vanilla = options.preferredGraphicsBackend();
        int matches = 0;
        for (OptionInstance<?> option : original) {
            if (option == vanilla) matches++;
        }
        if (matches == 0) return;

        OptionInstance<?>[] changed = new OptionInstance<?>[original.length - matches];
        int out = 0;
        for (OptionInstance<?> option : original) {
            if (option != vanilla) changed[out++] = option;
        }
        cir.setReturnValue(changed);
    }
}
