package com.asbestosstar.nativeaccelerator.client;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.asbestosstar.nativeaccelerator.cache.PersistentResourceCache;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.resources.model.ClientItemInfoLoader;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.PlaceholderLookupProvider;
import net.minecraft.util.StrictJsonParser;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** High-throughput replacement for ClientItemInfoLoader.scheduleLoad. */
public final class ClientItemLoadBatcher {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter LISTER = FileToIdConverter.json("items");
    private static final boolean BATCH_LOADING = com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("model.batchItemLoading", true);
    private static final boolean FAST_ITEM_JSON = com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("model.fastItemJson", true);
    private static final boolean VERIFY_FAST_PATHS = com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig.booleanValue("model.verifyFastPaths", false);

    private ClientItemLoadBatcher() {}

    public static boolean enabled() {
        return BATCH_LOADING;
    }

    public static CompletableFuture<ClientItemInfoLoader.LoadedClientInfos> scheduleLoad(
            ResourceManager manager, Executor minecraftExecutor) {
        long wallStarted = ModelDagProfiler.begin();
        RegistryAccess.Frozen staticRegistries = ClientRegistryLayer.createRegistryAccess().compositeAccess();
        Executor workerExecutor = ModelWorkScheduler.executor(minecraftExecutor);

        CompletableFuture<ClientItemInfoLoader.LoadedClientInfos> future = CompletableFuture.supplyAsync(() -> {
            long started = ModelPipelineProfiler.start();
            Map<Identifier, Resource> resources = ReloadResourceIndex.resources(LISTER, manager);
            ModelPipelineProfiler.end("item.enumerate", started);
            ModelPipelineProfiler.addCount("item.resources", resources.size());
            return new ArrayList<>(resources.entrySet());
        }, workerExecutor).thenCompose(entries -> ModelWorkScheduler.mapIndexed(
                entries.size(), minecraftExecutor,
                index -> parse(entries.get(index), staticRegistries), "item.dynamic"))
                .thenApply(loads -> {
                    HashMap<Identifier, ClientItem> result = new HashMap<>(capacity(loads.size()));
                    for (PendingLoad load : loads) {
                        if (load != null && load.item() != null) result.put(load.id(), load.item());
                    }
                    return new ClientItemInfoLoader.LoadedClientInfos(result);
                });

        ModelDagProfiler.track("item-definitions", future, wallStarted);
        return future;
    }

    private static PendingLoad parse(Map.Entry<Identifier, Resource> entry, RegistryAccess.Frozen staticRegistries) {
        Identifier id = LISTER.fileToId(entry.getKey());
        Resource resource = entry.getValue();
        long totalStarted = ModelPipelineProfiler.start();
        try {
            if (FAST_ITEM_JSON) {
                try (Reader reader = PersistentResourceCache.reader("items", entry.getKey(), resource)) {
                    ClientItem item = FastClientItemDecoder.decode(reader);
                    ModelPipelineProfiler.addCount("item.fast-hit-count", 1);
                    if (VERIFY_FAST_PATHS) {
                        ClientItem vanilla = vanillaParse(id, resource, staticRegistries);
                        if (!item.equals(vanilla)) {
                            ModelPipelineProfiler.addCount("item.verify-mismatch", 1);
                            item = vanilla;
                        } else {
                            ModelPipelineProfiler.addCount("item.verify-match", 1);
                        }
                    }
                    return new PendingLoad(id, item);
                } catch (FastClientItemDecoder.UnsupportedFastPathException unsupported) {
                    FastPathFallbacks.record("item", unsupported, entry.getKey().toString());
                    ModelPipelineProfiler.addCount("item.fast-fallback-count", 1);
                    ModelPipelineProfiler.addCount("item.fast-fallback." + unsupported.reason(), 1);
                } catch (Exception fastFailure) {
                    FastPathFallbacks.record("item", fastFailure, entry.getKey().toString());
                    // Malformed or semantically tricky input is retried through vanilla so error handling stays
                    // authoritative and a future pack-format extension cannot silently produce the wrong model.
                    ModelPipelineProfiler.addCount("item.fast-fallback-count", 1);
                    ModelPipelineProfiler.addCount("item.fast-fallback.parse", 1);
                }
            }
            return new PendingLoad(id, vanillaParse(id, resource, staticRegistries));
        } catch (Exception exception) {
            LOGGER.error("Failed to open item model {} from pack '{}'", entry.getKey(), resource.sourcePackId(), exception);
            return new PendingLoad(id, null);
        } finally {
            ModelPipelineProfiler.end("item.resource.total", totalStarted);
        }
    }

    private static ClientItem vanillaParse(Identifier id, Resource resource, RegistryAccess.Frozen staticRegistries) throws Exception {
        long started = ModelPipelineProfiler.start();
        long cpuStarted = ModelPipelineProfiler.startThreadCpu();
        try (Reader reader = PersistentResourceCache.reader("items", LISTER.idToFile(id), resource)) {
            PlaceholderLookupProvider lookup = new PlaceholderLookupProvider(staticRegistries);
            RegistryOps<JsonElement> ops = lookup.createSerializationContext(JsonOps.INSTANCE);
            ClientItem parsed = ClientItem.CODEC.parse(ops, StrictJsonParser.parse(reader))
                    .ifError(error -> LOGGER.error("Couldn't parse item model '{}' from pack '{}': {}",
                            id, resource.sourcePackId(), error.message()))
                    .result().orElse(null);
            if (parsed != null && lookup.hasRegisteredPlaceholders()) {
                parsed = parsed.withRegistrySwapper(lookup.createSwapper());
            }
            return parsed;
        } finally {
            ModelPipelineProfiler.end("item.vanilla-codec", started);
            ModelPipelineProfiler.endThreadCpu("item.vanilla-codec", cpuStarted);
        }
    }

    private static int capacity(int size) {
        if (size < 12) return 16;
        return size >= (1 << 29) ? Integer.MAX_VALUE : (size * 4 / 3) + 1;
    }

    private record PendingLoad(Identifier id, ClientItem item) {}
}
