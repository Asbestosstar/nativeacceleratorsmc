package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.renderer.metal.GraphicsMenuText;
import com.asbestosstar.nativeaccelerator.renderer.metal.GraphicsTuningOptions;
import com.asbestosstar.nativeaccelerator.renderer.metal.MetalGraphicsOption;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Reorganises Minecraft 26.3's Graphics screen as a small CDE/Motif-style control panel.
 *
 * <p>The vanilla screen normally presents Display, Quality and Preferences as one long Minecraft
 * scrolling list.  In the UNIX theme those sections become menu-bar pages.  Only one page is visible
 * at a time, which changes the information architecture as well as the chrome while preserving the
 * real vanilla OptionInstance widgets and their behavior.</p>
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsMetalOptionMixin extends OptionsSubScreen {
    @Shadow @Final private LinearLayout header;

    @Unique private List<?> nativeaccelerator$displayEntries = List.of();
    @Unique private List<?> nativeaccelerator$qualityEntries = List.of();
    @Unique private List<?> nativeaccelerator$preferenceEntries = List.of();
    @Unique private List<?> nativeaccelerator$nativeEntries = List.of();
    @Unique private static final int NATIVEACCELERATOR_PAGE_DISPLAY = 0;
    @Unique private static final int NATIVEACCELERATOR_PAGE_QUALITY = 1;
    @Unique private static final int NATIVEACCELERATOR_PAGE_PREFERENCES = 2;
    @Unique private static final int NATIVEACCELERATOR_PAGE_NATIVE = 3;
    @Unique private int nativeaccelerator$page = NATIVEACCELERATOR_PAGE_DISPLAY;

    protected VideoSettingsMetalOptionMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    /** Add a compact application menu bar below the normal title. */
    @Inject(method = "addTitle()V", at = @At("TAIL"), require = 1)
    private void nativeaccelerator$addCdeMenuBar(CallbackInfo ci) {
        if (!NativeAcceleratorConfig.booleanValue("graphics.unixTheme", true)) return;

        LinearLayout menu = LinearLayout.horizontal().spacing(2);
        menu.addChild(nativeaccelerator$menuButton("nativeaccelerator.menu.display", NATIVEACCELERATOR_PAGE_DISPLAY, 82));
        menu.addChild(nativeaccelerator$menuButton("nativeaccelerator.menu.quality", NATIVEACCELERATOR_PAGE_QUALITY, 82));
        menu.addChild(nativeaccelerator$menuButton("nativeaccelerator.menu.preferences", NATIVEACCELERATOR_PAGE_PREFERENCES, 104));
        menu.addChild(nativeaccelerator$menuButton("nativeaccelerator.menu.native", NATIVEACCELERATOR_PAGE_NATIVE, 138));
        this.header.addChild(menu);
    }

    @Unique
    private Button nativeaccelerator$menuButton(String key, int page, int width) {
        return Button.builder(GraphicsMenuText.component(this.options, key), button -> nativeaccelerator$showPage(page))
                .width(width)
                .build();
    }

    /**
     * Let vanilla create its real controls first, then split those exact entries into CDE menu pages.
     * The fixed entry counts correspond to Minecraft 26.3's documented addOptions() structure:
     * Display=6 rows, Quality=11 rows, Preferences=the remaining rows.
     */
    @Inject(method = "addOptions()V", at = @At("RETURN"), require = 1)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void nativeaccelerator$buildCdePages(CallbackInfo ci) {
        OptionsList optionsList = this.list;
        if (optionsList == null) return;

        // When the theme is disabled, retain the ordinary vanilla list and only prepend our renderer selector.
        if (!NativeAcceleratorConfig.booleanValue("graphics.unixTheme", true)) {
            List old = new ArrayList(optionsList.children());
            optionsList.replaceEntries(Collections.emptyList());
            optionsList.addBig(MetalGraphicsOption.forOptions(this.options));
            MetalGraphicsOption.markInsertedByDisplayOptions(this.options);
            List withRenderer = new ArrayList(optionsList.children());
            withRenderer.addAll(old);
            optionsList.replaceEntries(withRenderer);
            return;
        }

        List all = new ArrayList(optionsList.children());
        if (all.size() < 20) {
            // Fail open on a future Minecraft version/modded shape rather than dropping unknown options.
            optionsList.addHeader(GraphicsMenuText.component(this.options, "nativeaccelerator.options.graphics.header"));
            optionsList.addBig(MetalGraphicsOption.forOptions(this.options));
            GraphicsTuningOptions.addControlsTo(optionsList, this.options);
            System.err.println("[Native Accelerator] CDE page split skipped: unexpected vanilla Graphics row count=" + all.size());
            return;
        }

        this.nativeaccelerator$displayEntries = new ArrayList(all.subList(0, 6));
        this.nativeaccelerator$qualityEntries = new ArrayList(all.subList(6, 17));
        this.nativeaccelerator$preferenceEntries = new ArrayList(all.subList(17, all.size()));

        // Build the Native Accelerator pane using normal OptionInstance widgets, then retain those entries.
        optionsList.replaceEntries(Collections.emptyList());
        optionsList.addHeader(GraphicsMenuText.component(this.options, "nativeaccelerator.options.graphics.header"));
        optionsList.addBig(MetalGraphicsOption.forOptions(this.options));
        MetalGraphicsOption.markInsertedByDisplayOptions(this.options);
        GraphicsTuningOptions.addControlsTo(optionsList, this.options);
        this.nativeaccelerator$nativeEntries = new ArrayList(optionsList.children());

        nativeaccelerator$showPage(NATIVEACCELERATOR_PAGE_DISPLAY);
        System.out.println("[Native Accelerator] CDE Graphics control panel active; pages=Display,Quality,Preferences,Native");
    }

    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void nativeaccelerator$showPage(int page) {
        this.nativeaccelerator$page = page;
        if (this.list == null) return;
        List entries;
        if (page == NATIVEACCELERATOR_PAGE_QUALITY) {
            entries = (List)this.nativeaccelerator$qualityEntries;
        } else if (page == NATIVEACCELERATOR_PAGE_PREFERENCES) {
            entries = (List)this.nativeaccelerator$preferenceEntries;
        } else if (page == NATIVEACCELERATOR_PAGE_NATIVE) {
            entries = (List)this.nativeaccelerator$nativeEntries;
        } else {
            entries = (List)this.nativeaccelerator$displayEntries;
        }
        if (!entries.isEmpty()) {
            this.list.replaceEntries((Collection)entries);
            this.list.setScrollAmount(0.0);
        }
    }

    /** Remove the vanilla three-value graphics-backend widget; Native Accelerator owns that choice. */
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
        for (OptionInstance<?> option : original) if (option == vanilla) matches++;
        if (matches == 0) return original;
        OptionInstance<?>[] changed = new OptionInstance<?>[original.length - matches];
        int out = 0;
        for (OptionInstance<?> option : original) if (option != vanilla) changed[out++] = option;
        return changed;
    }

    /** Compatibility fallback for builds/mods that invoke the private helper directly. */
    @Inject(
            method = "displayOptions(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void nativeaccelerator$replaceGraphicsApiOption(
            Options options, CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        OptionInstance<?>[] original = cir.getReturnValue();
        if (original == null || original.length == 0) return;
        OptionInstance<?> vanilla = options.preferredGraphicsBackend();
        int matches = 0;
        for (OptionInstance<?> option : original) if (option == vanilla) matches++;
        if (matches == 0) return;
        OptionInstance<?>[] changed = new OptionInstance<?>[original.length - matches];
        int out = 0;
        for (OptionInstance<?> option : original) if (option != vanilla) changed[out++] = option;
        cir.setReturnValue(changed);
    }

}
