package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.serialization.Codec;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/** User-facing Native Accelerator renderer preference, independent of Minecraft's fixed enum. */
public enum GraphicsApiChoice implements StringRepresentable {
    DEFAULT("default", "nativeaccelerator.graphicsApi.default"),
    OPENGL("opengl", "nativeaccelerator.graphicsApi.opengl"),
    VULKAN("vulkan", "nativeaccelerator.graphicsApi.vulkan"),
    METAL("metal", "nativeaccelerator.graphicsApi.metal");

    public static final Codec<GraphicsApiChoice> CODEC = StringRepresentable.fromEnum(GraphicsApiChoice::values);

    private final String serializedName;
    private final String captionKey;

    GraphicsApiChoice(String serializedName, String captionKey) {
        this.serializedName = serializedName;
        this.captionKey = captionKey;
    }

    public Component caption(Options options) {
        return GraphicsMenuText.component(options, this.captionKey);
    }

    @Override
    public String getSerializedName() {
        return this.serializedName;
    }

    public static GraphicsApiChoice fromSerializedName(String name) {
        if (name != null) {
            for (GraphicsApiChoice value : values()) {
                if (value.serializedName.equalsIgnoreCase(name.trim())) return value;
            }
        }
        return DEFAULT;
    }
}
