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
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final ConcurrentHashMap<StateDefinition<?, ?>, SelectorLayout> SELECTOR_LAYOUTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ParsedSelector> PARSED_SELECTORS = new ConcurrentHashMap<>();

    private FastBlockStateDecoder() {}

    public static IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> decodeAndInstantiate(
            Reader input,
            StateDefinition<Block, BlockState> stateDefinition,
            String source) throws IOException {
        long totalStarted = ModelPipelineProfiler.start();
        long totalCpuStarted = ModelPipelineProfiler.startThreadCpu();
        JsonReader reader = new JsonReader(input);
        reader.setLenient(false);

        List<BlockState> possibleStates = stateDefinition.getPossibleStates();
        VariantAccumulator matched = new VariantAccumulator(possibleStates);
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
            matched.fillEmpty(model);
            if (started != 0L) {
                ModelPipelineProfiler.record("blockstate.fast.multipart.install",
                        System.nanoTime() - started, possibleStates.size());
            }
        }

        if (totalStarted != 0L) {
            ModelPipelineProfiler.record("blockstate.fast.total", System.nanoTime() - totalStarted, 1);
        }
        ModelPipelineProfiler.endThreadCpu("blockstate.fast.total", totalCpuStarted);
        return matched.toIdentityMap();
    }

    private static void parseVariants(
            JsonReader reader,
            StateDefinition<Block, BlockState> definition,
            String source,
            VariantAccumulator output) throws IOException {
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
            VariantAccumulator output) {
        long started = ModelPipelineProfiler.start();
        long cpuStarted = ModelPipelineProfiler.startThreadCpu();

        // Empty variant selectors are common and mean "all states". They do not need the global
        // StateDefinition layout or selector caches at all. Preserve vanilla overlap semantics: if this
        // is the first root, fill the dense accumulator directly; otherwise write in state order until
        // the first overlap throws.
        if (selector.isEmpty()) {
            long matchedCount = output.putAllNoOverlap(root);
            if (started != 0L) {
                ModelPipelineProfiler.record("blockstate.fast.variant.empty",
                        System.nanoTime() - started, Math.max(1L, matchedCount));
            }
            ModelPipelineProfiler.endThreadCpu("blockstate.fast.variant.empty", cpuStarted,
                    Math.max(1L, matchedCount));
            return;
        }

        SelectorLayout layout = SELECTOR_LAYOUTS.computeIfAbsent(definition, SelectorLayout::new);
        CompiledSelector compiled = layout.compile(selector);
        long matchedCount = compiled.emit(output, root);
        String stage = compiled.constraintCount == layout.propertyCount
                ? "blockstate.fast.variant.direct"
                : "blockstate.fast.variant.partial";
        if (started != 0L) {
            ModelPipelineProfiler.record(stage, System.nanoTime() - started, Math.max(1L, matchedCount));
        }
        ModelPipelineProfiler.endThreadCpu(stage, cpuStarted, Math.max(1L, matchedCount));
    }

    /**
     * Precomputed state masks for one StateDefinition. Most definitions have at most 64 states, so a
     * partial selector becomes a handful of long ANDs plus trailing-zero iteration. Large definitions use
     * the same scheme with BitSet. This removes per-selector IdentityHashMap/ArrayList allocation and
     * recursive StateHolder transitions from the hot path.
     */
    private static final class SelectorLayout {
        final List<BlockState> states;
        final int propertyCount;
        final boolean small;
        final long allSmall;
        final BitSet allLarge;
        final Map<String, PropertySlot> propertiesByName;
        final ConcurrentHashMap<String, CompiledSelector> compiled = new ConcurrentHashMap<>();

        @SuppressWarnings({"unchecked", "rawtypes"})
        SelectorLayout(StateDefinition<?, ?> rawDefinition) {
            StateDefinition<Block, BlockState> definition = (StateDefinition) rawDefinition;
            this.states = List.copyOf(definition.getPossibleStates());
            this.propertyCount = definition.getProperties().size();
            this.small = states.size() <= Long.SIZE;
            this.allSmall = small ? (states.size() == Long.SIZE ? -1L : ((1L << states.size()) - 1L)) : 0L;
            this.allLarge = small ? null : new BitSet(states.size());
            if (allLarge != null) allLarge.set(0, states.size());

            HashMap<String, PropertySlot> byName = new HashMap<>(Math.max(4, propertyCount * 2));
            int propertyIndex = 0;
            for (Property<?> property : definition.getProperties()) {
                PropertySlot slot = new PropertySlot(propertyIndex++, property, states, small);
                byName.put(property.getName(), slot);
            }
            this.propertiesByName = Map.copyOf(byName);
            ModelPipelineProfiler.addCount("blockstate.selector-layout.states", states.size());
        }

        CompiledSelector compile(String selector) {
            CompiledSelector cached = compiled.get(selector);
            if (cached != null) {
                ModelPipelineProfiler.addCount("blockstate.selector-cache.hit", 1);
                return cached;
            }
            CompiledSelector created = compileNew(selector);
            CompiledSelector previous = compiled.putIfAbsent(selector, created);
            if (previous == null) ModelPipelineProfiler.addCount("blockstate.selector-cache.miss", 1);
            else ModelPipelineProfiler.addCount("blockstate.selector-cache.race-hit", 1);
            return previous == null ? created : previous;
        }

        private CompiledSelector compileNew(String selector) {
            ValueMask[] selected = new ValueMask[propertyCount];
            ParsedSelector parsed = PARSED_SELECTORS.get(selector);
            if (parsed == null) {
                ParsedSelector created = ParsedSelector.parse(selector);
                ParsedSelector raced = PARSED_SELECTORS.putIfAbsent(selector, created);
                parsed = raced == null ? created : raced;
                ModelPipelineProfiler.addCount(raced == null
                        ? "blockstate.selector-syntax-cache.miss"
                        : "blockstate.selector-syntax-cache.race-hit", 1);
            } else {
                ModelPipelineProfiler.addCount("blockstate.selector-syntax-cache.hit", 1);
            }
            for (SelectorTerm term : parsed.terms) {
                PropertySlot slot = propertiesByName.get(term.propertyName);
                if (slot == null) throw new RuntimeException("Unknown blockstate property: '" + term.propertyName + "'");
                ValueMask value = slot.valuesByText.get(term.valueName);
                if (value == null) {
                    throw new RuntimeException("Unknown value: '" + term.valueName + "' for blockstate property: '"
                            + term.propertyName + "' " + slot.property.getPossibleValues());
                }
                // Match vanilla selector-map behavior: a repeated property keeps its last term.
                selected[slot.index] = value;
            }

            int constraints = 0;
            if (small) {
                long mask = allSmall;
                for (ValueMask value : selected) {
                    if (value == null) continue;
                    constraints++;
                    mask &= value.smallMask;
                }
                return new CompiledSelector(constraints, mask, null);
            }
            BitSet mask = (BitSet) allLarge.clone();
            for (ValueMask value : selected) {
                if (value == null) continue;
                constraints++;
                mask.and(value.largeMask);
            }
            return new CompiledSelector(constraints, 0L, mask);
        }
    }

    private record SelectorTerm(String propertyName, String valueName) {}

    private static final class ParsedSelector {
        final List<SelectorTerm> terms;

        private ParsedSelector(List<SelectorTerm> terms) {
            this.terms = terms;
        }

        static ParsedSelector parse(String selector) {
            if (selector.isEmpty()) return new ParsedSelector(List.of());
            ArrayList<SelectorTerm> terms = new ArrayList<>(4);
            int length = selector.length();
            int start = 0;
            while (start <= length) {
                int comma = selector.indexOf(',', start);
                int end = comma < 0 ? length : comma;
                if (end > start) {
                    int equals = selector.indexOf('=', start);
                    if (equals < 0 || equals >= end) {
                        throw new RuntimeException("Unknown blockstate property: '"
                                + selector.substring(start, end) + "'");
                    }
                    terms.add(new SelectorTerm(selector.substring(start, equals),
                            selector.substring(equals + 1, end)));
                }
                if (comma < 0) break;
                start = comma + 1;
            }
            return new ParsedSelector(List.copyOf(terms));
        }
    }

    private static final class PropertySlot {
        final int index;
        final Property<?> property;
        final Map<String, ValueMask> valuesByText;

        @SuppressWarnings({"rawtypes", "unchecked"})
        PropertySlot(int index, Property<?> property, List<BlockState> states, boolean small) {
            this.index = index;
            this.property = property;
            HashMap<String, ValueMask> values = new HashMap<>();
            for (Comparable<?> candidate : comparableValues(property)) {
                long smallMask = 0L;
                BitSet largeMask = small ? null : new BitSet(states.size());
                for (int stateIndex = 0; stateIndex < states.size(); stateIndex++) {
                    if (!candidate.equals(getValue(states.get(stateIndex), property))) continue;
                    if (small) smallMask |= 1L << stateIndex;
                    else largeMask.set(stateIndex);
                }
                String serialized = ((Property) property).getName(candidate);
                values.put(serialized, new ValueMask(smallMask, largeMask));
            }
            this.valuesByText = Map.copyOf(values);
        }
    }

    private static final class ValueMask {
        final long smallMask;
        final BitSet largeMask;
        ValueMask(long smallMask, BitSet largeMask) {
            this.smallMask = smallMask;
            this.largeMask = largeMask;
        }
    }

    private static final class CompiledSelector {
        final int constraintCount;
        final long smallMask;
        final BitSet largeMask;
        CompiledSelector(int constraintCount, long smallMask, BitSet largeMask) {
            this.constraintCount = constraintCount;
            this.smallMask = smallMask;
            this.largeMask = largeMask;
        }

        long emit(VariantAccumulator output, BlockStateModel.UnbakedRoot root) {
            long count = 0L;
            if (largeMask == null) {
                long remaining = smallMask;
                while (remaining != 0L) {
                    int index = Long.numberOfTrailingZeros(remaining);
                    output.putNoOverlap(index, root);
                    remaining &= remaining - 1L;
                    count++;
                }
                return count;
            }
            for (int index = largeMask.nextSetBit(0); index >= 0; index = largeMask.nextSetBit(index + 1)) {
                output.putNoOverlap(index, root);
                count++;
            }
            return count;
        }
    }

    /**
     * Dense state-index accumulator for one blockstate document.  Vanilla's dispatcher ultimately produces
     * an IdentityHashMap, but doing an identity-hash lookup for every selector/state match is unnecessary
     * because SelectorLayout already works in the StateDefinition's stable state-index space.  We preserve
     * vanilla overlap semantics exactly: the new root is written first, then an overlap throws, so the
     * current variant stops while the overlapping state retains the new value.
     */
    private static final class VariantAccumulator {
        final List<BlockState> states;
        final BlockStateModel.UnbakedRoot[] roots;
        int populated;

        VariantAccumulator(List<BlockState> states) {
            this.states = states;
            this.roots = new BlockStateModel.UnbakedRoot[states.size()];
        }

        void putNoOverlap(int index, BlockStateModel.UnbakedRoot root) {
            BlockStateModel.UnbakedRoot previous = roots[index];
            roots[index] = root;
            if (previous == null) populated++;
            if (previous != null) {
                throw new IllegalArgumentException("Overlapping definition on state: " + states.get(index));
            }
        }

        long putAllNoOverlap(BlockStateModel.UnbakedRoot root) {
            if (populated == 0) {
                java.util.Arrays.fill(roots, root);
                populated = roots.length;
                return roots.length;
            }
            long count = 0L;
            for (int i = 0; i < roots.length; i++) {
                putNoOverlap(i, root);
                count++;
            }
            return count;
        }

        void fillEmpty(BlockStateModel.UnbakedRoot root) {
            for (int i = 0; i < roots.length; i++) {
                if (roots[i] != null) continue;
                roots[i] = root;
                populated++;
            }
        }

        IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> toIdentityMap() {
            IdentityHashMap<BlockState, BlockStateModel.UnbakedRoot> result =
                    new IdentityHashMap<>(Math.max(4, populated));
            for (int i = 0; i < roots.length; i++) {
                BlockStateModel.UnbakedRoot root = roots[i];
                if (root != null) result.put(states.get(i), root);
            }
            return result;
        }
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
                case "model" -> model = IdentifierInterner.parse(reader.nextString());
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


    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Iterable<? extends Comparable<?>> comparableValues(Property<?> property) {
        return (Iterable) property.getPossibleValues();
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
