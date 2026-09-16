package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.startup.StartupStages;
import com.asbestosstar.nativeaccelerator.startup.StartupTimer;
import net.minecraft.server.Bootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Startup timing for {@code net.minecraft.server.Bootstrap}, the common bootstrap that both the client and
 * a dedicated server execute before anything else.
 *
 * <p>This is the single most informative place to measure "what parts of startup are the slowest": the
 * whole method is timed, and so is every heavyweight call it makes. Sub-steps are timed with a pair of
 * injections on the {@code INVOKE} itself - one {@link Shift#BEFORE} the call and one
 * {@link Shift#AFTER} - so each stage is exactly the call, with no source-level guessing. The calls are
 * what {@link Bootstrap} really makes; this holds even where the decompiled source is imprecise.</p>
 *
 * <p>{@code net.minecraft.server.Bootstrap} exists and runs on both sides, which is why this Mixin lives
 * in {@code mixin.common} rather than {@code mixin.client} or {@code mixin.server}.</p>
 *
 * <p>Reversibility: every injection uses {@code require = 0}, so if Mojang renames or removes one call the
 * game still starts and only that one number is missing. The handlers only touch {@link StartupTimer},
 * which never throws and is disabled entirely by {@code -Dnativeaccelerator.startup.timing=false}.</p>
 */
@Mixin(Bootstrap.class)
public abstract class BootstrapMixin {

    /* ---- the bootstrap as a whole ---------------------------------------------------------- */

    @Inject(method = "bootStrap", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$bootstrapBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.BOOTSTRAP);
    }

    @Inject(method = "bootStrap", at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$bootstrapEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.BOOTSTRAP);
    }

    /* ---- bootstrap sub-steps --------------------------------------------------------------- */

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.BEFORE,
                    target = "Lnet/minecraft/world/level/block/FireBlock;bootStrap()V"))
    private static void nativeaccelerator$fireBlockBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.BOOTSTRAP_FIRE);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.AFTER,
                    target = "Lnet/minecraft/world/level/block/FireBlock;bootStrap()V"))
    private static void nativeaccelerator$fireBlockEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.BOOTSTRAP_FIRE);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.BEFORE,
                    target = "Lnet/minecraft/commands/arguments/selector/options/EntitySelectorOptions;bootStrap()V"))
    private static void nativeaccelerator$selectorsBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.BOOTSTRAP_SELECTORS);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.AFTER,
                    target = "Lnet/minecraft/commands/arguments/selector/options/EntitySelectorOptions;bootStrap()V"))
    private static void nativeaccelerator$selectorsEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.BOOTSTRAP_SELECTORS);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.BEFORE,
                    target = "Lnet/minecraft/core/dispenser/DispenseItemBehavior;bootStrap()V"))
    private static void nativeaccelerator$dispenseBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.BOOTSTRAP_DISPENSE);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.AFTER,
                    target = "Lnet/minecraft/core/dispenser/DispenseItemBehavior;bootStrap()V"))
    private static void nativeaccelerator$dispenseEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.BOOTSTRAP_DISPENSE);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.BEFORE,
                    target = "Lnet/minecraft/core/cauldron/CauldronInteractions;bootStrap()V"))
    private static void nativeaccelerator$cauldronBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.BOOTSTRAP_CAULDRON);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.AFTER,
                    target = "Lnet/minecraft/core/cauldron/CauldronInteractions;bootStrap()V"))
    private static void nativeaccelerator$cauldronEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.BOOTSTRAP_CAULDRON);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.BEFORE,
                    target = "Lnet/minecraft/core/registries/BuiltInRegistries;bootStrap()V"))
    private static void nativeaccelerator$registriesBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.BOOTSTRAP_REGISTRIES);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.AFTER,
                    target = "Lnet/minecraft/core/registries/BuiltInRegistries;bootStrap()V"))
    private static void nativeaccelerator$registriesEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.BOOTSTRAP_REGISTRIES);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.BEFORE,
                    target = "Lnet/minecraft/world/item/CreativeModeTabs;validate()V"))
    private static void nativeaccelerator$creativeTabsBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.BOOTSTRAP_CREATIVE_TABS);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.AFTER,
                    target = "Lnet/minecraft/world/item/CreativeModeTabs;validate()V"))
    private static void nativeaccelerator$creativeTabsEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.BOOTSTRAP_CREATIVE_TABS);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.BEFORE,
                    target = "Lnet/minecraft/world/level/storage/loot/parameters/LootContextParamSets;validate()V"))
    private static void nativeaccelerator$lootContextBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.BOOTSTRAP_LOOT_CONTEXT);
    }

    @Inject(method = "bootStrap", require = 0,
            at = @At(value = "INVOKE", shift = Shift.AFTER,
                    target = "Lnet/minecraft/world/level/storage/loot/parameters/LootContextParamSets;validate()V"))
    private static void nativeaccelerator$lootContextEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.BOOTSTRAP_LOOT_CONTEXT);
    }

    /* ---- the second bootstrap phase -------------------------------------------------------- */

    @Inject(method = "validate", at = @At("HEAD"), require = 0)
    private static void nativeaccelerator$validateBegin(CallbackInfo ci) {
        StartupTimer.begin(StartupStages.BOOTSTRAP_VALIDATE);
    }

    @Inject(method = "validate", at = @At("RETURN"), require = 0)
    private static void nativeaccelerator$validateEnd(CallbackInfo ci) {
        StartupTimer.end(StartupStages.BOOTSTRAP_VALIDATE);
    }
}
