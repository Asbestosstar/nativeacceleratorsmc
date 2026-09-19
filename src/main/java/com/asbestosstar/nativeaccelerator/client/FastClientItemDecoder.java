package com.asbestosstar.nativeaccelerator.client;

import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.math.Transformation;
import net.minecraft.client.color.item.Constant;
import net.minecraft.client.color.item.CustomModelDataSource;
import net.minecraft.client.color.item.Dye;
import net.minecraft.client.color.item.Firework;
import net.minecraft.client.color.item.GrassColorSource;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.color.item.Potion;
import net.minecraft.client.color.item.TeamColor;
import net.minecraft.client.renderer.item.BundleSelectedItemSpecialRenderer;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.ConditionalItemModel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.EmptyModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.RangeSelectItemModel;
import net.minecraft.client.renderer.item.SelectItemModel;
import net.minecraft.client.renderer.item.properties.conditional.Broken;
import net.minecraft.client.renderer.item.properties.conditional.BundleHasSelectedItem;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty;
import net.minecraft.client.renderer.item.properties.conditional.Damaged;
import net.minecraft.client.renderer.item.properties.conditional.ExtendedView;
import net.minecraft.client.renderer.item.properties.conditional.FishingRodCast;
import net.minecraft.client.renderer.item.properties.conditional.HasComponent;
import net.minecraft.client.renderer.item.properties.conditional.IsCarried;
import net.minecraft.client.renderer.item.properties.conditional.IsSelected;
import net.minecraft.client.renderer.item.properties.conditional.IsUsingItem;
import net.minecraft.client.renderer.item.properties.conditional.IsViewEntity;
import net.minecraft.client.renderer.item.properties.numeric.BundleFullness;
import net.minecraft.client.renderer.item.properties.numeric.CompassAngle;
import net.minecraft.client.renderer.item.properties.numeric.CompassAngleState;
import net.minecraft.client.renderer.item.properties.numeric.Cooldown;
import net.minecraft.client.renderer.item.properties.numeric.Count;
import net.minecraft.client.renderer.item.properties.numeric.CrossbowPull;
import net.minecraft.client.renderer.item.properties.numeric.Damage;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.client.renderer.item.properties.numeric.Time;
import net.minecraft.client.renderer.item.properties.numeric.UseCycle;
import net.minecraft.client.renderer.item.properties.numeric.UseDuration;
import net.minecraft.client.renderer.item.properties.select.Charge;
import net.minecraft.client.renderer.item.properties.select.DisplayContext;
import net.minecraft.client.renderer.item.properties.select.ItemBlockState;
import net.minecraft.client.renderer.item.properties.select.MainHand;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperty;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.AxisAngle4f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Conservative streaming fast path for common 26.3 item-definition shapes.
 *
 * <p>Pass 7 extends the original model/composite path to the common condition, range_dispatch and select
 * dispatchers whose properties can be constructed exactly without RegistryOps. Transformations are decoded
 * with the same vector/quaternion/matrix representations accepted by Transformation.EXTENDED_CODEC. Registry-
 * contextual properties and any unknown field still throw {@link UnsupportedFastPathException}; the caller
 * reopens the resource and uses Minecraft's codec as the semantic authority.</p>
 */
public final class FastClientItemDecoder {
    private FastClientItemDecoder() {}

    public static ClientItem decode(Reader input) throws IOException, UnsupportedFastPathException {
        long started = ModelPipelineProfiler.start();
        long cpuStarted = ModelPipelineProfiler.startThreadCpu();
        JsonReader reader = new JsonReader(input);
        reader.setLenient(false); // Gson 2.10.1 compatible strict parsing.
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
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        require(reader.peek() == JsonToken.END_DOCUMENT, "trailing item JSON content");
        require(model != null, "item definition missing model");
        ModelPipelineProfiler.end("item.fast.decode", started);
        ModelPipelineProfiler.endThreadCpu("item.fast.decode", cpuStarted);
        return new ClientItem(model, new ClientItem.Properties(handAnimation, oversized, swapScale));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemModel.Unbaked parseModel(JsonReader reader) throws IOException, UnsupportedFastPathException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "item model must be an object");
        String type = null;
        Identifier modelId = null;
        List<ItemModel.Unbaked> children = null;
        List<ItemTintSource> tints = null;
        Optional<Transformation> transformation = Optional.empty();
        boolean sawTransformation = false;

        String property = null;
        Integer index = null;
        Boolean normalize = null;
        Boolean remaining = null;
        Boolean wobble = null;
        Boolean ignoreDefault = null;
        Float period = null;
        Float scale = null;
        String source = null;
        String target = null;
        String component = null;
        String blockStateProperty = null;

        ItemModel.Unbaked onTrue = null;
        ItemModel.Unbaked onFalse = null;
        ItemModel.Unbaked fallback = null;
        List<RangeSelectItemModel.Entry> rangeEntries = null;
        List<RawSwitchCase> rawCases = null;
        String unsupportedField = null;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "type" -> type = reader.nextString();
                case "model" -> modelId = IdentifierInterner.parse(reader.nextString());
                case "models" -> children = parseModels(reader);
                case "tints" -> tints = parseTints(reader);
                case "transformation" -> {
                    transformation = Optional.of(parseTransformation(reader));
                    sawTransformation = true;
                }
                case "property" -> property = reader.nextString();
                case "index" -> index = reader.nextInt();
                case "normalize" -> normalize = reader.nextBoolean();
                case "remaining" -> remaining = reader.nextBoolean();
                case "wobble" -> wobble = reader.nextBoolean();
                case "ignore_default" -> ignoreDefault = reader.nextBoolean();
                case "period" -> period = (float) reader.nextDouble();
                case "scale" -> scale = (float) reader.nextDouble();
                case "source" -> source = reader.nextString();
                case "target" -> target = reader.nextString();
                case "component" -> component = reader.nextString();
                case "block_state_property" -> blockStateProperty = reader.nextString();
                case "on_true" -> onTrue = parseModel(reader);
                case "on_false" -> onFalse = parseModel(reader);
                case "fallback" -> fallback = parseModel(reader);
                case "entries" -> rangeEntries = parseRangeEntries(reader);
                case "cases" -> rawCases = parseCases(reader);
                default -> {
                    unsupportedField = field;
                    reader.skipValue();
                }
            }
        }
        reader.endObject();

        if (unsupportedField != null) throw new UnsupportedFastPathException("field-" + safeReason(unsupportedField));
        if (type == null) throw new UnsupportedFastPathException("missing-type");
        String normalized = IdentifierInterner.parse(type).toString();
        return switch (normalized) {
            case "minecraft:model" -> {
                if (modelId == null) throw new JsonParseException("minecraft:model missing model id");
                yield new CuboidItemModelWrapper.Unbaked(modelId, transformation, tints == null ? List.of() : tints);
            }
            case "minecraft:composite" -> {
                if (children == null) throw new JsonParseException("minecraft:composite missing models");
                yield new CompositeModel.Unbaked(children, transformation);
            }
            case "minecraft:empty" -> {
                if (sawTransformation) throw new UnsupportedFastPathException("empty-transformation");
                yield new EmptyModel.Unbaked();
            }
            case "minecraft:bundle/selected_item" -> {
                if (sawTransformation) throw new UnsupportedFastPathException("bundle-selected-transformation");
                yield new BundleSelectedItemSpecialRenderer.Unbaked();
            }
            case "minecraft:condition" -> {
                require(property != null, "minecraft:condition missing property");
                require(onTrue != null, "minecraft:condition missing on_true");
                require(onFalse != null, "minecraft:condition missing on_false");
                ConditionalItemModelProperty p = parseConditionalProperty(property, index, component, ignoreDefault);
                yield new ConditionalItemModel.Unbaked(transformation, p, onTrue, onFalse);
            }
            case "minecraft:range_dispatch" -> {
                require(property != null, "minecraft:range_dispatch missing property");
                require(rangeEntries != null, "minecraft:range_dispatch missing entries");
                RangeSelectItemModelProperty p = parseRangeProperty(property, index, normalize, remaining,
                        wobble, period, source, target);
                yield new RangeSelectItemModel.Unbaked(transformation, p,
                        scale == null ? 1.0f : scale, rangeEntries, Optional.ofNullable(fallback));
            }
            case "minecraft:select" -> {
                require(property != null, "minecraft:select missing property");
                require(rawCases != null, "minecraft:select missing cases");
                SelectPropertyAndCases parsed = parseSelectPropertyAndCases(property, index, blockStateProperty, rawCases);
                yield new SelectItemModel.Unbaked(transformation,
                        new SelectItemModel.UnbakedSwitch(parsed.property, parsed.cases), Optional.ofNullable(fallback));
            }
            default -> throw new UnsupportedFastPathException("type-" + safeReason(normalized));
        };
    }

    private static List<ItemModel.Unbaked> parseModels(JsonReader reader) throws IOException, UnsupportedFastPathException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "composite models must be an array");
        ArrayList<ItemModel.Unbaked> parsed = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) parsed.add(parseModel(reader));
        reader.endArray();
        return List.copyOf(parsed);
    }

    private static List<RangeSelectItemModel.Entry> parseRangeEntries(JsonReader reader)
            throws IOException, UnsupportedFastPathException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "range entries must be an array");
        ArrayList<RangeSelectItemModel.Entry> result = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            require(reader.peek() == JsonToken.BEGIN_OBJECT, "range entry must be an object");
            Float threshold = null;
            ItemModel.Unbaked model = null;
            String unsupported = null;
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                switch (field) {
                    case "threshold" -> threshold = (float) reader.nextDouble();
                    case "model" -> model = parseModel(reader);
                    default -> { unsupported = field; reader.skipValue(); }
                }
            }
            reader.endObject();
            if (unsupported != null) throw new UnsupportedFastPathException("range-entry-field-" + safeReason(unsupported));
            require(threshold != null, "range entry missing threshold");
            require(model != null, "range entry missing model");
            result.add(new RangeSelectItemModel.Entry(threshold, model));
        }
        reader.endArray();
        return List.copyOf(result);
    }

    private static List<RawSwitchCase> parseCases(JsonReader reader) throws IOException, UnsupportedFastPathException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "select cases must be an array");
        ArrayList<RawSwitchCase> result = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            require(reader.peek() == JsonToken.BEGIN_OBJECT, "select case must be an object");
            List<String> when = null;
            ItemModel.Unbaked model = null;
            String unsupported = null;
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                switch (field) {
                    case "when" -> when = parseCompactStringList(reader);
                    case "model" -> model = parseModel(reader);
                    default -> { unsupported = field; reader.skipValue(); }
                }
            }
            reader.endObject();
            if (unsupported != null) throw new UnsupportedFastPathException("select-case-field-" + safeReason(unsupported));
            require(when != null && !when.isEmpty(), "select case missing/non-empty when");
            require(model != null, "select case missing model");
            result.add(new RawSwitchCase(when, model));
        }
        reader.endArray();
        return List.copyOf(result);
    }

    private static List<String> parseCompactStringList(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.STRING) return List.of(reader.nextString());
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "select when must be a string or string list");
        ArrayList<String> values = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            require(reader.peek() == JsonToken.STRING, "select when values must be strings");
            values.add(reader.nextString());
        }
        reader.endArray();
        return List.copyOf(values);
    }

    private static ConditionalItemModelProperty parseConditionalProperty(String property, Integer index,
            String component, Boolean ignoreDefault) throws UnsupportedFastPathException {
        String p = IdentifierInterner.parse(property).toString();
        return switch (p) {
            case "minecraft:using_item" -> new IsUsingItem();
            case "minecraft:broken" -> new Broken();
            case "minecraft:damaged" -> new Damaged();
            case "minecraft:fishing_rod/cast" -> new FishingRodCast();
            case "minecraft:bundle/has_selected_item" -> new BundleHasSelectedItem();
            case "minecraft:selected" -> new IsSelected();
            case "minecraft:carried" -> new IsCarried();
            case "minecraft:extended_view" -> new ExtendedView();
            case "minecraft:view_entity" -> new IsViewEntity();
            case "minecraft:custom_model_data" -> new net.minecraft.client.renderer.item.properties.conditional.CustomModelDataProperty(
                    nonNegative(index == null ? 0 : index, "condition.custom_model_data.index"));
            case "minecraft:has_component" -> {
                if (component == null) throw new JsonParseException("has_component missing component");
                Identifier id = IdentifierInterner.parse(component);
                DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
                if (type == null) throw new JsonParseException("Unknown data component type: " + id);
                yield new HasComponent(type, ignoreDefault != null && ignoreDefault);
            }
            default -> throw new UnsupportedFastPathException("condition-property-" + safeReason(p));
        };
    }

    private static RangeSelectItemModelProperty parseRangeProperty(String property, Integer index,
            Boolean normalize, Boolean remaining, Boolean wobble, Float period, String source, String target)
            throws UnsupportedFastPathException {
        String p = IdentifierInterner.parse(property).toString();
        return switch (p) {
            case "minecraft:custom_model_data" -> new net.minecraft.client.renderer.item.properties.numeric.CustomModelDataProperty(
                    nonNegative(index == null ? 0 : index, "range.custom_model_data.index"));
            case "minecraft:bundle/fullness" -> new BundleFullness();
            case "minecraft:damage" -> new Damage(normalize == null || normalize);
            case "minecraft:cooldown" -> new Cooldown();
            case "minecraft:crossbow/pull" -> new CrossbowPull();
            case "minecraft:use_cycle" -> new UseCycle(positive(period == null ? 1.0f : period, "use_cycle.period"));
            case "minecraft:use_duration" -> new UseDuration(remaining != null && remaining);
            case "minecraft:count" -> new Count(normalize == null || normalize);
            case "minecraft:time" -> {
                if (source == null) throw new JsonParseException("time property missing source");
                yield new Time(wobble == null || wobble, switch (source) {
                    case "random" -> Time.TimeSource.RANDOM;
                    case "daytime" -> Time.TimeSource.DAYTIME;
                    case "moon_phase" -> Time.TimeSource.MOON_PHASE;
                    default -> throw new JsonParseException("Unknown time source: " + source);
                });
            }
            case "minecraft:compass" -> {
                if (target == null) throw new JsonParseException("compass property missing target");
                yield new CompassAngle(wobble == null || wobble, switch (target) {
                    case "none" -> CompassAngleState.CompassTarget.NONE;
                    case "lodestone" -> CompassAngleState.CompassTarget.LODESTONE;
                    case "spawn" -> CompassAngleState.CompassTarget.SPAWN;
                    case "recovery" -> CompassAngleState.CompassTarget.RECOVERY;
                    default -> throw new JsonParseException("Unknown compass target: " + target);
                });
            }
            default -> throw new UnsupportedFastPathException("range-property-" + safeReason(p));
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SelectPropertyAndCases parseSelectPropertyAndCases(String property, Integer index,
            String blockStateProperty, List<RawSwitchCase> rawCases) throws UnsupportedFastPathException {
        String p = IdentifierInterner.parse(property).toString();
        SelectItemModelProperty selectProperty;
        ValueParser parser;
        switch (p) {
            case "minecraft:custom_model_data" -> {
                selectProperty = new net.minecraft.client.renderer.item.properties.select.CustomModelDataProperty(
                        nonNegative(index == null ? 0 : index, "select.custom_model_data.index"));
                parser = value -> value;
            }
            case "minecraft:block_state" -> {
                if (blockStateProperty == null) throw new JsonParseException("block_state property missing block_state_property");
                selectProperty = new ItemBlockState(blockStateProperty);
                parser = value -> value;
            }
            case "minecraft:charge_type" -> {
                selectProperty = new Charge();
                parser = value -> switch (value) {
                    case "none" -> CrossbowItem.ChargeType.NONE;
                    case "arrow" -> CrossbowItem.ChargeType.ARROW;
                    case "rocket" -> CrossbowItem.ChargeType.ROCKET;
                    default -> throw new JsonParseException("Unknown charge_type value: " + value);
                };
            }
            case "minecraft:main_hand" -> {
                selectProperty = new MainHand();
                parser = value -> switch (value) {
                    case "left" -> HumanoidArm.LEFT;
                    case "right" -> HumanoidArm.RIGHT;
                    default -> throw new JsonParseException("Unknown main_hand value: " + value);
                };
            }
            case "minecraft:display_context" -> {
                selectProperty = new DisplayContext();
                parser = FastClientItemDecoder::displayContext;
            }
            default -> throw new UnsupportedFastPathException("select-property-" + safeReason(p));
        }

        ArrayList<SelectItemModel.SwitchCase> cases = new ArrayList<>(rawCases.size());
        for (RawSwitchCase raw : rawCases) {
            ArrayList<Object> values = new ArrayList<>(raw.values.size());
            for (String value : raw.values) values.add(parser.parse(value));
            cases.add(new SelectItemModel.SwitchCase(List.copyOf(values), raw.model));
        }
        return new SelectPropertyAndCases(selectProperty, List.copyOf(cases));
    }

    private static ItemDisplayContext displayContext(String value) {
        return switch (value) {
            case "none" -> ItemDisplayContext.NONE;
            case "thirdperson_lefthand" -> ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
            case "thirdperson_righthand" -> ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            case "firstperson_lefthand" -> ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
            case "firstperson_righthand" -> ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
            case "head" -> ItemDisplayContext.HEAD;
            case "gui" -> ItemDisplayContext.GUI;
            case "ground" -> ItemDisplayContext.GROUND;
            case "fixed" -> ItemDisplayContext.FIXED;
            case "on_shelf" -> ItemDisplayContext.ON_SHELF;
            default -> throw new JsonParseException("Unknown display_context value: " + value);
        };
    }

    private static Transformation parseTransformation(JsonReader reader) throws IOException, UnsupportedFastPathException {
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            float[] m = readFloatArray(reader, 16, "transformation matrix");
            Matrix4f matrix = new Matrix4f();
            for (int i = 0; i < 16; i++) matrix.setRowColumn(i >> 2, i & 3, m[i]);
            matrix.determineProperties();
            return new Transformation(matrix);
        }
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "transformation must be object or 16-float matrix");
        Vector3f translation = null;
        Quaternionf left = null;
        Vector3f scale = null;
        Quaternionf right = null;
        String unsupported = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "translation" -> translation = vector3(reader, "translation");
                case "left_rotation" -> left = quaternion(reader, "left_rotation");
                case "scale" -> scale = vector3(reader, "scale");
                case "right_rotation" -> right = quaternion(reader, "right_rotation");
                default -> { unsupported = field; reader.skipValue(); }
            }
        }
        reader.endObject();
        if (unsupported != null) throw new UnsupportedFastPathException("transformation-field-" + safeReason(unsupported));
        require(translation != null && left != null && scale != null && right != null,
                "transformation object requires translation,left_rotation,scale,right_rotation");
        return new Transformation(translation, left, scale, right);
    }

    private static Vector3f vector3(JsonReader reader, String field) throws IOException {
        float[] v = readFloatArray(reader, 3, field);
        return new Vector3f(v[0], v[1], v[2]);
    }

    private static Quaternionf quaternion(JsonReader reader, String field) throws IOException {
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            float[] q = readFloatArray(reader, 4, field);
            return new Quaternionf(q[0], q[1], q[2], q[3]).normalize();
        }
        require(reader.peek() == JsonToken.BEGIN_OBJECT, field + " must be quaternion components or axis-angle");
        Float angle = null;
        Vector3f axis = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "angle" -> angle = (float) reader.nextDouble();
                case "axis" -> axis = vector3(reader, field + ".axis");
                default -> throw new JsonParseException("Unknown " + field + " axis-angle field: " + name);
            }
        }
        reader.endObject();
        require(angle != null && axis != null, field + " axis-angle requires angle and axis");
        return new Quaternionf(new AxisAngle4f(angle, axis));
    }

    private static float[] readFloatArray(JsonReader reader, int count, String field) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, field + " must be an array");
        float[] values = new float[count];
        int i = 0;
        reader.beginArray();
        while (reader.hasNext()) {
            if (i >= count) throw new JsonParseException(field + " has too many values");
            values[i++] = (float) reader.nextDouble();
        }
        reader.endArray();
        require(i == count, field + " must contain exactly " + count + " values");
        return values;
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

    private static float positive(float value, String field) {
        if (!(value > 0.0f)) throw new JsonParseException(field + " must be positive");
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

    private record RawSwitchCase(List<String> values, ItemModel.Unbaked model) {}
    @SuppressWarnings("rawtypes")
    private record SelectPropertyAndCases(SelectItemModelProperty property, List<SelectItemModel.SwitchCase> cases) {}
    @FunctionalInterface private interface ValueParser { Object parse(String value); }

    public static final class UnsupportedFastPathException extends Exception {
        private final String reason;
        public UnsupportedFastPathException(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
        public String reason() { return reason; }
    }
}

