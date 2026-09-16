package com.asbestosstar.nativeaccelerator.client;

import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.client.color.item.Constant;
import net.minecraft.client.color.item.CustomModelDataSource;
import net.minecraft.client.color.item.Dye;
import net.minecraft.client.color.item.Firework;
import net.minecraft.client.color.item.GrassColorSource;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.color.item.Potion;
import net.minecraft.client.color.item.TeamColor;
import net.minecraft.client.renderer.item.BundleSelectedItemSpecialRenderer;
import net.minecraft.util.ARGB;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.EmptyModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Conservative streaming fast path for the common item-definition shapes.
 *
 * <p>It intentionally recognizes only shapes that can be constructed exactly without RegistryOps:
 * plain {@code minecraft:model}, {@code minecraft:empty}, {@code minecraft:bundle/selected_item}, and
 * composites composed entirely from those same shapes. Common vanilla item tint payloads are decoded
 * directly; transformation/property-driven model types still fall back. Everything
 * else throws {@link UnsupportedFastPathException} and is reparsed by Minecraft's authoritative codec.</p>
 */
public final class FastClientItemDecoder {
    private FastClientItemDecoder() {}

    public static ClientItem decode(Reader input) throws IOException, UnsupportedFastPathException {
        long started = ModelPipelineProfiler.start();
        JsonReader reader = new JsonReader(input);
        // Gson 2.10.1 compatible strict parsing. Do not use the 2.11 Strictness API here.
        reader.setLenient(false);
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "item root must be an object");

        ItemModel.Unbaked model = null;
        boolean handAnimation = true;
        boolean oversized = false;
        float swapScale = 1.0f;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "model" -> model = parseModel(reader);
                case "hand_animation_on_swap" -> handAnimation = reader.nextBoolean();
                case "oversized_in_gui" -> oversized = reader.nextBoolean();
                case "swap_animation_scale" -> swapScale = (float) reader.nextDouble();
                default -> reader.skipValue(); // DataFixer map codecs tolerate unrelated top-level fields.
            }
        }
        reader.endObject();
        require(reader.peek() == JsonToken.END_DOCUMENT, "trailing item JSON content");
        require(model != null, "item definition missing model");
        ModelPipelineProfiler.end("item.fast.decode", started);
        return new ClientItem(model, new ClientItem.Properties(handAnimation, oversized, swapScale));
    }

    private static ItemModel.Unbaked parseModel(JsonReader reader) throws IOException, UnsupportedFastPathException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "item model must be an object");
        String type = null;
        Identifier modelId = null;
        List<ItemModel.Unbaked> children = null;
        List<ItemTintSource> tints = null;
        String unsupportedField = null;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "type" -> type = reader.nextString();
                case "model" -> modelId = IdentifierInterner.parse(reader.nextString());
                case "models" -> {
                    require(reader.peek() == JsonToken.BEGIN_ARRAY, "composite models must be an array");
                    ArrayList<ItemModel.Unbaked> parsed = new ArrayList<>();
                    reader.beginArray();
                    while (reader.hasNext()) parsed.add(parseModel(reader));
                    reader.endArray();
                    children = List.copyOf(parsed);
                }
                case "tints" -> tints = parseTints(reader);
                // Transformation remains on Minecraft's codec path until exact parity coverage is complete.
                case "transformation" -> {
                    unsupportedField = field;
                    reader.skipValue();
                }
                default -> {
                    unsupportedField = field;
                    reader.skipValue();
                }
            }
        }
        reader.endObject();

        if (unsupportedField != null) throw new UnsupportedFastPathException("field-" + unsupportedField);
        if (type == null) throw new UnsupportedFastPathException("missing-type");
        String normalized = IdentifierInterner.parse(type).toString();
        return switch (normalized) {
            case "minecraft:model" -> {
                if (modelId == null) throw new JsonParseException("minecraft:model missing model id");
                yield new CuboidItemModelWrapper.Unbaked(modelId, Optional.empty(), tints == null ? List.of() : tints);
            }
            case "minecraft:empty" -> new EmptyModel.Unbaked();
            case "minecraft:bundle/selected_item" -> new BundleSelectedItemSpecialRenderer.Unbaked();
            case "minecraft:composite" -> {
                if (children == null) throw new JsonParseException("minecraft:composite missing models");
                yield new CompositeModel.Unbaked(children, Optional.empty());
            }
            default -> throw new UnsupportedFastPathException("type-" + safeReason(normalized));
        };
    }

    private static List<ItemTintSource> parseTints(JsonReader reader) throws IOException, UnsupportedFastPathException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "tints must be an array");
        ArrayList<ItemTintSource> result = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) result.add(parseTint(reader));
        reader.endArray();
        return List.copyOf(result);
    }

    private static ItemTintSource parseTint(JsonReader reader) throws IOException, UnsupportedFastPathException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "tint must be an object");
        String type = null;
        Integer value = null;
        Integer defaultColor = null;
        Integer index = null;
        Float temperature = null;
        Float downfall = null;
        String unsupported = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "type" -> type = reader.nextString();
                case "value" -> value = parseRgb(reader);
                case "default" -> defaultColor = parseRgb(reader);
                case "index" -> index = reader.nextInt();
                case "temperature" -> temperature = (float) reader.nextDouble();
                case "downfall" -> downfall = (float) reader.nextDouble();
                default -> { unsupported = field; reader.skipValue(); }
            }
        }
        reader.endObject();
        if (unsupported != null) throw new UnsupportedFastPathException("tint-field-" + safeReason(unsupported));
        if (type == null) throw new UnsupportedFastPathException("tint-missing-type");
        String normalized = IdentifierInterner.parse(type).toString();
        return switch (normalized) {
            case "minecraft:constant" -> new Constant(requireColor(value, "constant.value"));
            case "minecraft:custom_model_data" -> new CustomModelDataSource(
                    index == null ? 0 : nonNegative(index, "custom_model_data.index"),
                    requireColor(defaultColor, "custom_model_data.default"));
            case "minecraft:dye" -> new Dye(requireColor(defaultColor, "dye.default"));
            case "minecraft:firework" -> new Firework(requireColor(defaultColor, "firework.default"));
            case "minecraft:grass" -> {
                float t = unit(requireFloat(temperature, "grass.temperature"), "grass.temperature");
                float d = unit(requireFloat(downfall, "grass.downfall"), "grass.downfall");
                yield new GrassColorSource(t, d);
            }
            case "minecraft:potion" -> new Potion(requireColor(defaultColor, "potion.default"));
            case "minecraft:team" -> new TeamColor(requireColor(defaultColor, "team.default"));
            default -> throw new UnsupportedFastPathException("tint-type-" + safeReason(normalized));
        };
    }

    /** ExtraCodecs.RGB_COLOR_CODEC accepts either an int or a [r,g,b] float vector. */
    private static int parseRgb(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NUMBER) return reader.nextInt();
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "RGB color must be int or [r,g,b]");
        reader.beginArray();
        float r = (float) reader.nextDouble();
        float g = (float) reader.nextDouble();
        float b = (float) reader.nextDouble();
        require(!reader.hasNext(), "RGB vector must contain exactly three values");
        reader.endArray();
        return ARGB.colorFromFloat(1.0f, r, g, b);
    }

    private static int requireColor(Integer value, String field) {
        if (value == null) throw new JsonParseException(field + " is required");
        return value;
    }

    private static float requireFloat(Float value, String field) {
        if (value == null) throw new JsonParseException(field + " is required");
        return value;
    }

    private static int nonNegative(int value, String field) {
        if (value < 0) throw new JsonParseException(field + " must be non-negative");
        return value;
    }

    private static float unit(float value, String field) {
        if (!(value >= 0.0f && value <= 1.0f)) throw new JsonParseException(field + " must be in [0,1]");
        return value;
    }

    private static String safeReason(String value) {
        StringBuilder out = new StringBuilder(Math.min(48, value.length()));
        for (int i = 0; i < value.length() && out.length() < 48; i++) {
            char c = value.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return out.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new JsonParseException(message);
    }

    public static final class UnsupportedFastPathException extends Exception {
        private final String reason;
        public UnsupportedFastPathException(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
        public String reason() { return reason; }
    }
}
