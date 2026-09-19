package com.asbestosstar.nativeaccelerator.cache;

import com.asbestosstar.nativeaccelerator.client.ModelPipelineProfiler;
import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Persists deterministic Stitcher placements for an unchanged sprite set. */
public final class PersistentAtlasLayoutCache {
    private static final int MAGIC = 0x4E414C59; // NALY
    private static final int VERSION = 1;
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("cache.atlasLayout", true);
    private static final Object LOCK = new Object();
    private static final Set<String> PENDING_KEYS = ConcurrentHashMap.newKeySet();
    private static volatile String generation;
    private static volatile PersistentBlobCache blobs;

    private PersistentAtlasLayoutCache() {}

    public static Layout load(Identifier atlas, List<SpriteContents> sprites,
            int maxTextureSize, int mipLevel, int anisotropyBit) {
        PersistentBlobCache cache = cache();
        if (cache == null || hasDuplicateNames(sprites)) return null;
        String key = key(atlas, sprites, maxTextureSize, mipLevel, anisotropyBit);
        byte[] bytes = cache.get(key);
        if (bytes == null) {
            ModelPipelineProfiler.addCount("atlas.layout-cache.miss", 1);
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            int width = in.readInt();
            int height = in.readInt();
            int storedMip = in.readInt();
            int padding = in.readInt();
            int count = in.readInt();
            if (width <= 0 || height <= 0 || width > maxTextureSize || height > maxTextureSize
                    || storedMip != mipLevel || count != sprites.size()) return null;
            HashMap<Identifier, Position> positions = new HashMap<>(Math.max(16, count * 4 / 3 + 1));
            HashMap<Identifier, SpriteContents> current = new HashMap<>(Math.max(16, count * 4 / 3 + 1));
            for (SpriteContents sprite : sprites) current.put(sprite.name(), sprite);
            for (int i = 0; i < count; i++) {
                Identifier id = Identifier.parse(in.readUTF());
                int spriteWidth = in.readInt();
                int spriteHeight = in.readInt();
                int x = in.readInt();
                int y = in.readInt();
                SpriteContents live = current.get(id);
                if (live == null || live.width() != spriteWidth || live.height() != spriteHeight
                        || x < 0 || y < 0 || x >= width || y >= height) return null;
                positions.put(id, new Position(x, y));
            }
            if (in.available() != 0 || positions.size() != count) return null;
            ModelPipelineProfiler.addCount("atlas.layout-cache.hit", 1);
            return new Layout(width, height, mipLevel, padding, Map.copyOf(positions));
        } catch (Exception failure) {
            ModelPipelineProfiler.addCount("atlas.layout-cache.invalid", 1);
            return null;
        }
    }

    /** Serializes the small layout record now, but defers all persistent I/O until after startup/reload. */
    public static void store(Identifier atlas, List<SpriteContents> sprites,
            int maxTextureSize, int mipLevel, int anisotropyBit,
            int width, int height, int padding, Map<Identifier, TextureAtlasSprite> result) {
        PersistentBlobCache cache = cache();
        if (cache == null || hasDuplicateNames(sprites) || result.size() != sprites.size()) return;
        String key = key(atlas, sprites, maxTextureSize, mipLevel, anisotropyBit);
        if (cache.contains(key) || !PENDING_KEYS.add(key)) return;
        String expectedGeneration = generation;
        final byte[] payload;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(256, sprites.size() * 48));
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(width);
                out.writeInt(height);
                out.writeInt(mipLevel);
                out.writeInt(padding);
                out.writeInt(sprites.size());
                for (SpriteContents sprite : sprites) {
                    TextureAtlasSprite placed = result.get(sprite.name());
                    if (placed == null) {
                        PENDING_KEYS.remove(key);
                        return;
                    }
                    out.writeUTF(sprite.name().toString());
                    out.writeInt(sprite.width());
                    out.writeInt(sprite.height());
                    out.writeInt(placed.getX());
                    out.writeInt(placed.getY());
                }
            }
            payload = bytes.toByteArray();
        } catch (IOException ignored) {
            PENDING_KEYS.remove(key);
            ModelPipelineProfiler.addCount("atlas.layout-cache.store-error", 1);
            return;
        }

        boolean accepted = DeferredCacheWriter.submit("atlas-layout", payload.length, () -> {
            try {
                if (PersistentResourceCache.generationMatches(expectedGeneration)) {
                    cache.put(key, payload);
                    ModelPipelineProfiler.addCount("atlas.layout-cache.store", 1);
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

    private static boolean hasDuplicateNames(List<SpriteContents> sprites) {
        HashSet<Identifier> ids = new HashSet<>(Math.max(16, sprites.size() * 4 / 3 + 1));
        for (SpriteContents sprite : sprites) if (!ids.add(sprite.name())) return true;
        return false;
    }

    private static String key(Identifier atlas, List<SpriteContents> sprites,
            int maxTextureSize, int mipLevel, int anisotropyBit) {
        MessageDigest digest = digest();
        update(digest, atlas.toString());
        update(digest, "\0" + maxTextureSize + ':' + mipLevel + ':' + anisotropyBit + '\n');
        for (SpriteContents sprite : sprites) {
            update(digest, sprite.name() + "\0" + sprite.width() + 'x' + sprite.height() + '\n');
        }
        return "layout\u0000" + HexFormat.of().formatHex(digest.digest());
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
                blobs = new PersistentBlobCache(environment.generationDirectory(), "atlas-layouts");
                generation = wanted;
                return blobs;
            } catch (IOException failure) {
                blobs = null;
                generation = wanted;
                return null;
            }
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    public record Position(int x, int y) {}
    public record Layout(int width, int height, int mipLevel, int padding, Map<Identifier, Position> positions) {}
}
