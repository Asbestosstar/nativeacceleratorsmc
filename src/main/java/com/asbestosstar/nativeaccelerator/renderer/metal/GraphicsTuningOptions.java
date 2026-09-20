package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.mixin.client.OptionInstanceCaptionAccessor;
import com.mojang.serialization.Codec;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

import java.util.List;

/** Native Accelerator graphics-related controls shown above Minecraft's normal video settings. */
public final class GraphicsTuningOptions {
    private GraphicsTuningOptions() {}

    public static void addTo(OptionsList list, Options options) {
        list.addHeader(GraphicsMenuText.component(options, "nativeaccelerator.options.graphics.header"));
        addControlsTo(list, options);
    }

    /** Adds the controls without a header so the CDE menu can build its own pane. */
    public static void addControlsTo(OptionsList list, Options options) {
        list.addSmall(
                booleanOption(options, "nativeaccelerator.options.unixTheme", "graphics.unixTheme", true),
                booleanOption(options, "nativeaccelerator.options.crtScanlines", "graphics.crt.scanlines", true));
        list.addSmall(
                booleanOption(options, "nativeaccelerator.options.showUma", "graphics.crt.uma", true),
                intOption(options, "nativeaccelerator.options.umaOpacity", "graphics.crt.umaOpacity", 92, 0, 100));

        list.addHeader(GraphicsMenuText.component(options, "nativeaccelerator.options.graphics.pipeline"));
        list.addSmall(
                enumOption(options, "nativeaccelerator.options.nativeVulkan", "renderer.vulkan", GraphicsMode.AUTO),
                intOption(options, "nativeaccelerator.options.rendererWorkers", "renderer.workers", 0, 0, 64));
        list.addSmall(
                booleanOption(options, "nativeaccelerator.options.fastStitcher", "atlas.fastStitcher", true),
                booleanOption(options, "nativeaccelerator.options.safeFastStitcher", "atlas.safeFastStitcher", true));
        list.addSmall(
                booleanOption(options, "nativeaccelerator.options.verifyFastStitcher", "atlas.verifyFastStitcher", false),
                booleanOption(options, "nativeaccelerator.options.parallelMipmaps", "atlas.parallelMipmaps", true));
        list.addSmall(
                intOption(options, "nativeaccelerator.options.atlasWorkers", "atlas.workers", 0, 0, 64),
                booleanOption(options, "nativeaccelerator.options.decodedTextureCache", "cache.decodedTextures", true));
        list.addSmall(
                booleanOption(options, "nativeaccelerator.options.atlasLayoutCache", "cache.atlasLayout", true),
                booleanOption(options, "nativeaccelerator.options.requireVerifiedAtlas", "cache.atlasLayout.requireVerified", true));
        list.addSmall(
                booleanOption(options, "nativeaccelerator.options.guiNineSliceFix", "gui.fixButtonNineSliceSeam", true),
                intOption(options, "nativeaccelerator.options.metalFrames", "renderer.metal.framesInFlight", 3, 1, 3));
        list.addSmall(
                booleanOption(options, "nativeaccelerator.options.metalCpuIndirect", "renderer.metal.forceCpuIndirect", false),
                booleanOption(options, "nativeaccelerator.options.metalYFlip", "renderer.metal.generatedTextureYFlip", true));
        list.addSmall(
                intOption(options, "nativeaccelerator.options.mac1Frames", "renderer.metal.mac1FramesInFlight", 1, 1, 3),
                booleanOption(options, "nativeaccelerator.options.mac1CycleClearedTargets", "renderer.metal.mac1CycleClearedTargets", true));
    }

    private static OptionInstance<Boolean> booleanOption(Options options, String caption, String key, boolean fallback) {
        boolean current = NativeAcceleratorConfig.booleanValue(key, fallback);
        OptionInstance<Boolean> result = OptionInstance.createBoolean(
                caption,
                OptionInstance.cachedConstantTooltip(GraphicsMenuText.component(options, "nativeaccelerator.options.restart.tooltip")),
                current,
                value -> NativeAcceleratorConfig.setBoolean(key, value));
        localize(result, options, caption);
        return result;
    }

    private static OptionInstance<Integer> intOption(Options options, String caption, String key, int fallback, int min, int max) {
        int current = Math.max(min, Math.min(max, NativeAcceleratorConfig.intValue(key, fallback, min)));
        OptionInstance<Integer> result = new OptionInstance<>(
                caption,
                OptionInstance.cachedConstantTooltip(GraphicsMenuText.component(options, "nativeaccelerator.options.restart.tooltip")),
                (label, value) -> Options.genericValueLabel(label,
                        value == 0 ? GraphicsMenuText.component(options, "nativeaccelerator.options.auto") : Component.literal(Integer.toString(value))),
                new OptionInstance.IntRange(min, max),
                current,
                value -> NativeAcceleratorConfig.setInt(key, value));
        localize(result, options, caption);
        return result;
    }

    private static OptionInstance<GraphicsMode> enumOption(Options options, String caption, String key, GraphicsMode fallback) {
        GraphicsMode current = GraphicsMode.parse(NativeAcceleratorConfig.stringValue(key, fallback.getSerializedName()));
        OptionInstance<GraphicsMode> result = new OptionInstance<>(
                caption,
                OptionInstance.cachedConstantTooltip(GraphicsMenuText.component(options, "nativeaccelerator.options.restart.tooltip")),
                (label, value) -> Options.genericValueLabel(label, value.caption(options)),
                new OptionInstance.Enum<>(List.of(GraphicsMode.values()), GraphicsMode.CODEC),
                GraphicsMode.CODEC,
                current,
                value -> NativeAcceleratorConfig.setString(key, value.getSerializedName()));
        localize(result, options, caption);
        return result;
    }

    private static void localize(OptionInstance<?> option, Options options, String key) {
        ((OptionInstanceCaptionAccessor)(Object)option)
                .nativeaccelerator$setCaption(GraphicsMenuText.component(options, key));
    }

    enum GraphicsMode implements StringRepresentable {
        AUTO("auto", "nativeaccelerator.options.auto"),
        ON("on", "options.on"),
        OFF("off", "options.off");

        static final Codec<GraphicsMode> CODEC = StringRepresentable.fromEnum(GraphicsMode::values);
        private final String id;
        private final String captionKey;

        GraphicsMode(String id, String captionKey) {
            this.id = id;
            this.captionKey = captionKey;
        }

        Component caption(Options options) {
            if (this == ON) return Component.literal(options.languageCode.startsWith("es_") ? "Sí" : "On");
            if (this == OFF) return Component.literal(options.languageCode.startsWith("es_") ? "No" : "Off");
            return GraphicsMenuText.component(options, captionKey);
        }

        @Override public String getSerializedName() { return id; }

        static GraphicsMode parse(String raw) {
            if (raw != null) for (GraphicsMode value : values()) if (value.id.equalsIgnoreCase(raw.trim())) return value;
            return AUTO;
        }
    }
}
