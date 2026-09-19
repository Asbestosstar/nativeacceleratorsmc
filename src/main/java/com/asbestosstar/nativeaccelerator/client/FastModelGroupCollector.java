package com.asbestosstar.nativeaccelerator.client;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Allocation-reduced equivalent of ModelGroupCollector.build for the 26.3 named API. */
public final class FastModelGroupCollector {
    private FastModelGroupCollector() {}

    public static Object2IntMap<BlockState> build(BlockColors colors, BlockStateModelLoader.LoadedModels input) {
        long started = ModelPipelineProfiler.start();
        IdentityHashMap<Block, List<Property<?>>> coloringProperties = new IdentityHashMap<>();
        HashMap<GroupKey, ArrayList<BlockState>> groups = new HashMap<>();

        input.models().forEach((state, model) -> {
            Block block = state.getBlock();
            List<Property<?>> properties = coloringProperties.get(block);
            if (properties == null) {
                properties = List.copyOf(colors.getColoringProperties(block));
                coloringProperties.put(block, properties);
            }
            GroupKey key = new GroupKey(model.visualEqualityGroup(state), coloringValues(state, properties));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>(2)).add(state);
        });

        Object2IntOpenHashMap<BlockState> result = new Object2IntOpenHashMap<>();
        result.defaultReturnValue(-1);
        int nextGroup = 1;
        for (ArrayList<BlockState> states : groups.values()) {
            int modeledCount = 0;
            for (BlockState state : states) {
                if (state.getRenderShape() == RenderShape.MODEL) modeledCount++;
                else result.put(state, 0);
            }
            if (modeledCount <= 1) continue;
            int group = nextGroup++;
            for (BlockState state : states) {
                if (state.getRenderShape() == RenderShape.MODEL) result.put(state, group);
            }
        }
        ModelPipelineProfiler.record("model-groups.fast", System.nanoTime() - started, input.models().size());
        return result;
    }

    private static List<Object> coloringValues(BlockState state, List<Property<?>> properties) {
        if (properties.isEmpty()) return List.of();
        Object[] values = new Object[properties.size()];
        for (int i = 0; i < properties.size(); i++) values[i] = getValue(state, properties.get(i));
        return List.of(values);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object getValue(BlockState state, Property<?> property) {
        return state.getValue((Property) property);
    }

    private record GroupKey(Object equalityGroup, List<Object> coloringValues) {}
}

