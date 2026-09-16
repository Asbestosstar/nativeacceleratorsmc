package com.asbestosstar.nativeaccelerator.client;

import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.logging.LogUtils;
import com.mojang.math.Quadrant;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.Variant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Streaming decoder/compiler for Minecraft blockstate JSON.
 *
 * <p>The vanilla path builds a Gson DOM, feeds it through DataFixer codecs, builds dispatcher/condition
 * object graphs, then expands selectors in a second pass.  For blockstates those abstractions are much more
 * expensive than the data itself.  This class performs one strict streaming pass and constructs the final
 * {@code BlockState -> UnbakedRoot} mapping directly.  Standard variants and multipart syntax are covered;
 * callers retain a vanilla fallback for anything this fast path deliberately declines.</p>
 */
public final class FastBlockStateDecoder {
    private static final Logger LOGGER = LogUtils.getLogger();

    private FastBlockStateDecoder() {}

    public static IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> decodeAndInstantiate(
            Reader input,
            StateDefinition<Block, BlockState> stateDefinition,
            String source) throws IOException {
        long totalStarted = ModelPipelineProfiler.start();
        JsonReader reader = new JsonReader(input);
        reader.setLenient(false);

        IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> matched = new IdentityHashMap<>();
        List<MultiPartModel.Selector<BlockStateModel.Unbaked>> multipart = null;
        boolean sawVariants = false;
        boolean sawMultipart = false;

        require(reader.peek() == JsonToken.BEGIN_OBJECT, "Blockstate root must be an object");
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "variants" -> {
                    sawVariants = true;
                    long started = ModelPipelineProfiler.start();
                    parseVariants(reader, stateDefinition, source, matched);
                    ModelPipelineProfiler.end("blockstate.fast.variants", started);
                }
                case "multipart" -> {
                    sawMultipart = true;
                    long started = ModelPipelineProfiler.start();
                    multipart = parseMultipart(reader, stateDefinition);
                    ModelPipelineProfiler.end("blockstate.fast.multipart.decode", started);
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new JsonParseException("Did not consume the entire blockstate document");
        }
        if (!sawVariants && !sawMultipart) {
            throw new JsonParseException("Neither 'variants' nor 'multipart' found");
        }

        if (multipart != null) {
            long started = ModelPipelineProfiler.start();
            MultiPartModel.Unbaked model = new MultiPartModel.Unbaked(multipart);
            for (BlockState state : stateDefinition.getPossibleStates()) {
                matched.putIfAbsent(state, model);
            }
            ModelPipelineProfiler.record("blockstate.fast.multipart.install",
                    System.nanoTime() - started, stateDefinition.getPossibleStates().size());
        }

        ModelPipelineProfiler.record("blockstate.fast.total", System.nanoTime() - totalStarted, 1);
        return matched;
    }

    private static void parseVariants(
            JsonReader reader,
            StateDefinition<Block, BlockState> definition,
            String source,
            IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> output) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "'variants' must be an object");
        int variantCount = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            variantCount++;
            String selector = reader.nextName();
            BlockStateModel.Unbaked model = parseUnbakedModel(reader);
            BlockStateModel.UnbakedRoot root = model.asRoot();
            try {
                applyVariant(definition, selector, root, output);
            } catch (Exception exception) {
                // Vanilla catches selector/overlap failures per variant and continues loading the file.
                LOGGER.warn("Exception loading blockstate definition: '{}' for variant: '{}'", source, selector);
            }
        }
        reader.endObject();
        require(variantCount != 0, "variants must not be empty");
    }

    private static void applyVariant(
            StateDefinition<Block, BlockState> definition,
            String selector,
            BlockStateModel.UnbakedRoot root,
            IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> output) {
        long started = ModelPipelineProfiler.start();
        IdentityHashMap<Property<?>, Comparable<?>> constraints = parseSelector(definition, selector);
        int propertyCount = definition.getProperties().size();

        if (constraints.isEmpty()) {
            for (BlockState state : definition.getPossibleStates()) putNoOverlap(output, state, root);
            ModelPipelineProfiler.record("blockstate.fast.variant.empty", System.nanoTime() - started,
                    definition.getPossibleStates().size());
            return;
        }

        // Full selectors are by far the common case in vanilla assets. StateHolder's neighbour table turns
        // each property assignment into an O(1) transition, avoiding an O(variants * states) predicate scan.
        if (constraints.size() == propertyCount) {
            BlockState state = definition.any();
            for (Map.Entry<Property<?>, Comparable<?>> entry : constraints.entrySet()) {
                state = setValue(state, entry.getKey(), entry.getValue());
            }
            putNoOverlap(output, state, root);
            ModelPipelineProfiler.record("blockstate.fast.variant.direct", System.nanoTime() - started, 1);
            return;
        }

        // A StateDefinition is the Cartesian product of its properties.  Enumerating only unconstrained
        // properties visits exactly the matching states instead of rescanning every state for every selector.
        BlockState base = definition.any();
        for (Map.Entry<Property<?>, Comparable<?>> entry : constraints.entrySet()) {
            base = setValue(base, entry.getKey(), entry.getValue());
        }
        ArrayList<Property<?>> free = new ArrayList<>(Math.max(0, propertyCount - constraints.size()));
        for (Property<?> property : definition.getProperties()) {
            if (!constraints.containsKey(property)) free.add(property);
        }
        long matchedCount = emitCombinations(base, free, 0, root, output);
        ModelPipelineProfiler.record("blockstate.fast.variant.partial", System.nanoTime() - started,
                Math.max(1, matchedCount));
    }

    private static IdentityHashMap<Property<?>, Comparable<?>> parseSelector(
            StateDefinition<Block, BlockState> definition, String selector) {
        IdentityHashMap<Property<?>, Comparable<?>> result = new IdentityHashMap<>();
        int length = selector.length();
        int start = 0;
        while (start <= length) {
            int comma = selector.indexOf(',', start);
            int end = comma < 0 ? length : comma;
            if (end > start) {
                int equals = selector.indexOf('=', start);
                if (equals < 0 || equals >= end) {
                    throw new RuntimeException("Unknown blockstate property: '" + selector.substring(start, end) + "'");
                }
                String propertyName = selector.substring(start, equals);
                String valueName = selector.substring(equals + 1, end);
                Property<?> property = definition.getProperty(propertyName);
                if (property == null) {
                    throw new RuntimeException("Unknown blockstate property: '" + propertyName + "'");
                }
                Comparable<?> value = propertyValue(property, valueName);
                if (value == null) {
                    throw new RuntimeException("Unknown value: '" + valueName + "' for blockstate property: '"
                            + propertyName + "' " + property.getPossibleValues());
                }
                result.put(property, value);
            } // Empty comma-separated terms are ignored by vanilla VariantSelector.
            if (comma < 0) break;
            start = comma + 1;
        }
        return result;
    }

    private static List<MultiPartModel.Selector<BlockStateModel.Unbaked>> parseMultipart(
            JsonReader reader,
            StateDefinition<Block, BlockState> definition) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "'multipart' must be an array");
        ArrayList<MultiPartModel.Selector<BlockStateModel.Unbaked>> selectors = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            require(reader.peek() == JsonToken.BEGIN_OBJECT, "multipart selector must be an object");
            reader.beginObject();
            Predicate<BlockState> condition = state -> true;
            BlockStateModel.Unbaked model = null;
            while (reader.hasNext()) {
                String field = reader.nextName();
                switch (field) {
                    case "when" -> condition = parseCondition(reader, definition);
                    case "apply" -> model = parseUnbakedModel(reader);
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            require(model != null, "multipart selector missing 'apply'");
            selectors.add(new MultiPartModel.Selector<>(condition, model));
        }
        reader.endArray();
        require(!selectors.isEmpty(), "multipart must not be empty");
        return List.copyOf(selectors);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Predicate<BlockState> parseCondition(
            JsonReader reader,
            StateDefinition<Block, BlockState> definition) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "multipart 'when' must be an object");
        reader.beginObject();
        if (!reader.hasNext()) {
            reader.endObject();
            throw new JsonParseException("Empty multipart condition");
        }

        String firstName = reader.nextName();
        if ("AND".equals(firstName) || "OR".equals(firstName)) {
            boolean and = "AND".equals(firstName);
            require(reader.peek() == JsonToken.BEGIN_ARRAY, firstName + " condition must be an array");
            ArrayList<Predicate<BlockState>> terms = new ArrayList<>();
            reader.beginArray();
            while (reader.hasNext()) terms.add(parseCondition(reader, definition));
            reader.endArray();
            require(!reader.hasNext(), "Combiner condition must contain exactly one key");
            reader.endObject();
            Predicate<BlockState>[] array = terms.toArray(Predicate[]::new);
            if (and) {
                return state -> {
                    for (Predicate<BlockState> term : array) if (!term.test(state)) return false;
                    return true;
                };
            }
            return state -> {
                for (Predicate<BlockState> term : array) if (term.test(state)) return true;
                return false;
            };
        }

        ArrayList<Predicate<BlockState>> tests = new ArrayList<>();
        tests.add(parsePropertyCondition(definition, firstName, readConditionValue(reader)));
        while (reader.hasNext()) {
            String propertyName = reader.nextName();
            tests.add(parsePropertyCondition(definition, propertyName, readConditionValue(reader)));
        }
        reader.endObject();
        Predicate<BlockState>[] array = tests.toArray(Predicate[]::new);
        return state -> {
            for (Predicate<BlockState> test : array) if (!test.test(state)) return false;
            return true;
        };
    }

    private static String readConditionValue(JsonReader reader) throws IOException {
        return switch (reader.peek()) {
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> Boolean.toString(reader.nextBoolean());
            default -> throw new JsonParseException("Expected string/int/bool multipart property value");
        };
    }

    private static Predicate<BlockState> parsePropertyCondition(
            StateDefinition<Block, BlockState> definition,
            String propertyName,
            String expression) {
        Property<?> property = definition.getProperty(propertyName);
        if (property == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Unknown property '%s' on '%s'", propertyName, definition.getOwner()));
        }
        if (expression.isEmpty()) throw new IllegalArgumentException("Empty value for property");

        ArrayList<Comparable<?>> accepted = new ArrayList<>();
        for (Comparable<?> possible : property.getPossibleValues()) {
            if (termExpressionAccepts(property, possible, expression)) accepted.add(possible);
        }
        if (accepted.isEmpty()) return state -> false;
        if (accepted.size() == property.getPossibleValues().size()) return state -> true;
        if (accepted.size() == 1) {
            Comparable<?> expected = accepted.getFirst();
            return state -> expected.equals(getValue(state, property));
        }
        Comparable<?>[] values = accepted.toArray(Comparable[]::new);
        return state -> {
            Comparable<?> actual = getValue(state, property);
            for (Comparable<?> value : values) if (value.equals(actual)) return true;
            return false;
        };
    }

    private static boolean termExpressionAccepts(Property<?> property, Comparable<?> possible, String expression) {
        int start = 0;
        int length = expression.length();
        while (start <= length) {
            int separator = expression.indexOf('|', start);
            int end = separator < 0 ? length : separator;
            if (end == start) throw new IllegalArgumentException("Empty term in value '" + expression + "'");
            boolean negated = expression.charAt(start) == '!';
            int valueStart = negated ? start + 1 : start;
            if (valueStart == end) throw new IllegalArgumentException("Empty term in value '" + expression + "'");
            String valueText = expression.substring(valueStart, end);
            Comparable<?> parsed = propertyValue(property, valueText);
            if (parsed == null) {
                throw new RuntimeException(String.format(Locale.ROOT,
                        "Unknown value '%s' for property '%s'", valueText, property));
            }
            boolean equals = possible.equals(parsed);
            if (negated ? !equals : equals) return true;
            if (separator < 0) break;
            start = separator + 1;
        }
        return false;
    }

    private static BlockStateModel.Unbaked parseUnbakedModel(JsonReader reader) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> new SingleVariant.Unbaked(parseVariant(reader).variant());
            case BEGIN_ARRAY -> parseWeightedVariants(reader);
            default -> throw new JsonParseException("Expected blockstate variant object or array");
        };
    }

    private static WeightedVariants.Unbaked parseWeightedVariants(JsonReader reader) throws IOException {
        ArrayList<Weighted<BlockStateModel.Unbaked>> entries = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            ParsedVariant parsed = parseVariant(reader);
            require(parsed.weight() > 0, "Variant weight must be positive");
            entries.add(new Weighted<>(new SingleVariant.Unbaked(parsed.variant()), parsed.weight()));
        }
        reader.endArray();
        require(!entries.isEmpty(), "Weighted variant list must not be empty");
        return new WeightedVariants.Unbaked(WeightedList.of(entries));
    }

    private static ParsedVariant parseVariant(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "Variant must be an object");
        Identifier model = null;
        Quadrant x = Quadrant.R0;
        Quadrant y = Quadrant.R0;
        Quadrant z = Quadrant.R0;
        boolean uvLock = false;
        int weight = 1;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "model" -> model = Identifier.parse(reader.nextString());
                case "x" -> x = quadrant(reader.nextInt());
                case "y" -> y = quadrant(reader.nextInt());
                case "z" -> z = quadrant(reader.nextInt());
                case "uvlock" -> uvLock = reader.nextBoolean();
                case "weight" -> weight = reader.nextInt();
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        require(model != null, "Variant missing 'model'");
        return new ParsedVariant(new Variant(model, new Variant.SimpleModelState(x, y, z, uvLock)), weight);
    }

    private static Quadrant quadrant(int degrees) {
        int normalized = Math.floorMod(degrees, 360);
        return switch (normalized) {
            case 0 -> Quadrant.R0;
            case 90 -> Quadrant.R90;
            case 180 -> Quadrant.R180;
            case 270 -> Quadrant.R270;
            default -> throw new JsonParseException("Invalid rotation " + degrees + " found, only 0/90/180/270 allowed");
        };
    }

    private static void putNoOverlap(
            IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> output,
            BlockState state,
            BlockStateModel.UnbakedRoot root) {
        BlockStateModel.UnbakedRoot previous = output.put(state, root);
        if (previous != null) throw new IllegalArgumentException("Overlapping definition on state: " + state);
    }

    private static long emitCombinations(
            BlockState state,
            List<Property<?>> freeProperties,
            int index,
            BlockStateModel.UnbakedRoot root,
            IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> output) {
        if (index == freeProperties.size()) {
            putNoOverlap(output, state, root);
            return 1L;
        }
        Property<?> property = freeProperties.get(index);
        long emitted = 0L;
        for (Comparable<?> value : comparableValues(property)) {
            emitted += emitCombinations(setValue(state, property, value), freeProperties, index + 1, root, output);
        }
        return emitted;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Iterable<? extends Comparable<?>> comparableValues(Property<?> property) {
        return (Iterable) property.getPossibleValues();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setValue(BlockState state, Property<?> property, Comparable<?> value) {
        return state.setValue((Property) property, (Comparable) value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Comparable<?> getValue(BlockState state, Property<?> property) {
        return (Comparable<?>) state.getValue((Property) property);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Comparable<?> propertyValue(Property<?> property, String value) {
        return (Comparable<?>) ((Property) property).getValue(value).orElse(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new JsonParseException(message);
    }

    private record ParsedVariant(Variant variant, int weight) {}
}
