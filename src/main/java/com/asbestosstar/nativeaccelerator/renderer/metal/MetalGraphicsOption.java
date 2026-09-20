package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.asbestosstar.nativeaccelerator.mixin.client.OptionInstanceCaptionAccessor;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Creates Native Accelerator's four-value renderer option. */
public final class MetalGraphicsOption {
    private static final Map<Options, OptionInstance<GraphicsApiChoice>> INSTANCES = new WeakHashMap<>();
    private static final Map<Options, Boolean> INSERTED_BY_DISPLAY_OPTIONS = new WeakHashMap<>();

    private MetalGraphicsOption() {}

    public static synchronized OptionInstance<GraphicsApiChoice> forOptions(Options options) {
        return INSTANCES.computeIfAbsent(options, MetalGraphicsOption::create);
    }

    public static synchronized void markInsertedByDisplayOptions(Options options) {
        INSERTED_BY_DISPLAY_OPTIONS.put(options, Boolean.TRUE);
    }

    public static synchronized boolean wasInsertedByDisplayOptions(Options options) {
        return Boolean.TRUE.equals(INSERTED_BY_DISPLAY_OPTIONS.get(options));
    }

    private static OptionInstance<GraphicsApiChoice> create(Options options) {
        GraphicsApiChoice initial = GraphicsBackendPreference.initialChoice(options.preferredGraphicsBackend().get());
        System.out.println("[Native Accelerator] Renderer GUI option created; initial choice="
                + initial.getSerializedName());

        OptionInstance<GraphicsApiChoice> result = new OptionInstance<>(
                "nativeaccelerator.options.renderer",
                OptionInstance.cachedConstantTooltip(GraphicsMenuText.component(options, "nativeaccelerator.options.renderer.tooltip")),
                (caption, value) -> Options.genericValueLabel(caption, value.caption(options)),
                new OptionInstance.Enum<>(List.of(GraphicsApiChoice.values()), GraphicsApiChoice.CODEC),
                GraphicsApiChoice.CODEC,
                initial,
                value -> {
                    GraphicsBackendPreference.select(value);
                    options.preferredGraphicsBackend().set(GraphicsBackendPreference.vanillaMirror(value));
                    options.save();
                    System.out.println("[Native Accelerator] Renderer preference changed to "
                            + value.getSerializedName() + "; restart required");
                });
        ((OptionInstanceCaptionAccessor)(Object)result)
                .nativeaccelerator$setCaption(GraphicsMenuText.component(options, "nativeaccelerator.options.renderer"));
        return result;
    }
}
