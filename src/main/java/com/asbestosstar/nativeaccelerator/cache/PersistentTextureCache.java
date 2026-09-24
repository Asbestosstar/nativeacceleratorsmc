package com.asbestosstar.nativeaccelerator.cache;

import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import com.asbestosstar.nativeaccelerator.mixin.client.NativeImagePixelsAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent decoded RGBA cache for atlas sprites.
 *
 * <p>Warm hits restore pixels directly from the mapped cache. Cold misses never perform a synchronous file
 * write. Pass 5 snapshots the already-decoded RGBA into a bounded heap payload and queues the disk append for
 * after the initial playable screen (or after a later reload). This removes the cache's serialized write lock
 * and disk I/O from atlas workers while keeping a safe copy independent of NativeImage lifetime.</p>
 */
public final class PersistentTextureCache {
    private static final int MAGIC = 0x4E415449; // NATI
    private static final int VERSION = 2;
    private static final int HEADER_BYTES = 20;
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("cache.decodedTextures", true);
    private static final Object LOCK = new Object();
    private static final Set<String> PENDING_KEYS = ConcurrentHashMap.newKeySet();
    private static volatile String generation;
    private static volatile PersistentBlobCache blobs;

    private PersistentTextureCache() {}

    public static NativeImage load(Identifier id, Resource resource) {
        PersistentBlobCache cache = cache();
        if (cache == null || !PersistentResourceCache.cacheable(resource)) return null;
        String key = key(id, resource);
        long started = ModelPipelineProfiler.start();
        ByteBuffer data = cache.view(key);
        if (data == null || data.remaining() < HEADER_BYTES) {
            ModelPipelineProfiler.addCount("texture.decoded-cache.miss", 1);
            return null;
        }
        try {
            // Blob files are written with ByteBuffer's default BIG_ENDIAN header encoding.
            data.order(ByteOrder.BIG_ENDIAN);
            int magic = data.getInt();
            int version = data.getInt();
            int width = data.getInt();
            int height = data.getInt();
            int expectedCrc = data.getInt();
            long pixelBytes = (long) width * height * 4L;
            if (magic != MAGIC || version != VERSION || width <= 0 || height <= 0 || pixelBytes > Integer.MAX_VALUE
                    || data.remaining() != (int) pixelBytes) {
                ModelPipelineProfiler.addCount("texture.decoded-cache.invalid", 1);
                return null;
            }
            CRC32 crc = new CRC32();
            ByteBuffer check = data.duplicate();
            while (check.hasRemaining()) crc.update(check.get() & 0xff);
            if ((int) crc.getValue() != expectedCrc) {
                ModelPipelineProfiler.addCount("texture.decoded-cache.crc-failure", 1);
                return null;
            }
            NativeImage image = new NativeImage(width, height, false);
            long destination = ((NativeImagePixelsAccessor) (Object) image).nativeaccelerator$pixels();
            long source = MemoryUtil.memAddress(data) + data.position();
            MemoryUtil.memCopy(source, destination, pixelBytes);
            ModelPipelineProfiler.end("texture.decoded-cache.hit", started);
            ModelPipelineProfiler.addCount("texture.decoded-cache.hit-count", 1);
            ModelPipelineProfiler.addCount("texture.decoded-cache.hit-bytes", pixelBytes);
            return image;
        } catch (Throwable failure) {
            ModelPipelineProfiler.addCount("texture.decoded-cache.load-error", 1);
            return null;
        }
    }

    /**
     * Stages an RGBA cache entry without doing persistent I/O on the caller. The only synchronous cost on a
     * miss is one bounded memory copy of pixels that already exist because STB just decoded them.
     */
    public static void store(Identifier id, Resource resource, NativeImage image) {
        PersistentBlobCache cache = cache();
        if (cache == null || !PersistentResourceCache.cacheable(resource)
                || image == null || image.isClosed() || image.format() != NativeImage.Format.RGBA) return;

        long pixelBytesLong = (long) image.getWidth() * image.getHeight() * 4L;
        if (pixelBytesLong <= 0L || pixelBytesLong > Integer.MAX_VALUE - HEADER_BYTES) return;
        int pixelBytes = (int) pixelBytesLong;
        String key = key(id, resource);
        if (cache.contains(key) || !PENDING_KEYS.add(key)) return;

        String expectedGeneration = generation;
        long stageStarted = ModelPipelineProfiler.start();
        byte[] payload;
        try {
            payload = new byte[HEADER_BYTES + pixelBytes];
            ByteBuffer header = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
            header.putInt(MAGIC).putInt(VERSION).putInt(image.getWidth()).putInt(image.getHeight()).putInt(0);
            long sourceAddress = ((NativeImagePixelsAccessor) (Object) image).nativeaccelerator$pixels();
            if (sourceAddress == 0L) {
                PENDING_KEYS.remove(key);
                return;
            }
            ByteBuffer source = MemoryUtil.memByteBuffer(sourceAddress, pixelBytes);
            source.get(payload, HEADER_BYTES, pixelBytes);
            CRC32 crc = new CRC32();
            crc.update(payload, HEADER_BYTES, pixelBytes);
            ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putInt(16, (int) crc.getValue());
            ModelPipelineProfiler.end("texture.decoded-cache.stage-copy", stageStarted);
            ModelPipelineProfiler.addCount("texture.decoded-cache.stage-bytes", pixelBytes);
        } catch (Throwable failure) {
            PENDING_KEYS.remove(key);
            ModelPipelineProfiler.addCount("texture.decoded-cache.stage-error", 1);
            return;
        }

        boolean accepted = DeferredCacheWriter.submit("texture", payload.length, () -> {
            try {
                if (PersistentResourceCache.generationMatches(expectedGeneration)) {
                    cache.put(key, payload);
                    ModelPipelineProfiler.addCount("texture.decoded-cache.store", 1);
                    ModelPipelineProfiler.addCount("texture.decoded-cache.store-bytes", pixelBytes);
                }
            } finally {
                PENDING_KEYS.remove(key);
            }
        });
        if (!accepted) PENDING_KEYS.remove(key);
    }

    public static void reset() {
        synchronized (LOCK) {
            PENDING_KEYS.clear();
            if (blobs != null) blobs.close();
            blobs = null;
            generation = null;
        }
    }

    private static PersistentBlobCache cache() {
        if (!ENABLED) return null;
        PersistentCacheEnvironment.Snapshot environment = PersistentResourceCache.environment();
        if (environment == null || !environment.enabled()) return null;
        String wanted = environment.generation();
        PersistentBlobCache existing = blobs;
        if (existing != null && wanted.equals(generation)) return existing;
        synchronized (LOCK) {
            if (blobs != null && wanted.equals(generation)) return blobs;
            if (blobs != null) blobs.close();
            try {
                blobs = new PersistentBlobCache(environment.generationDirectory(), "decoded-textures");
                generation = wanted;
                return blobs;
            } catch (IOException failure) {
                blobs = null;
                generation = wanted;
                return null;
            }
        }
    }

    private static String key(Identifier id, Resource resource) {
        // New namespace prevents old unchecksummed entries from blocking replacement writes.
        return "rgba-v2\u0000" + id + '\u0000' + resource.sourcePackId();
    }
}

