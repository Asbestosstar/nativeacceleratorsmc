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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists deterministic Stitcher placements for an unchanged sprite set.
 *
 * <p>Version 3 deliberately refuses older layout records and is used as a verified placement hint by default.  A layout is reusable only
 * after the live padded rectangles have passed structural validation and (by default) the producer
 * marked the placement as verified against Minecraft's Stitcher.  This keeps the warm-start speedup
 * without allowing a stale/bad atlas placement to survive indefinitely.</p>
 */
public final class PersistentAtlasLayoutCache {
    private static final int MAGIC = 0x4E414C59; // NALY
    private static final int VERSION = 3;
    private static final int POLICY_VERSION = 3;
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("cache.atlasLayout", true);
    private static final boolean REQUIRE_VERIFIED =
            NativeAcceleratorConfig.booleanValue("cache.atlasLayout.requireVerified", true);
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
        int expectedPadding = padding(mipLevel, anisotropyBit);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return invalid();
            int width = in.readInt();
            int height = in.readInt();
            int storedMip = in.readInt();
            int storedPadding = in.readInt();
            boolean verified = in.readBoolean();
            int count = in.readInt();
            if (width <= 0 || height <= 0 || width > maxTextureSize || height > maxTextureSize
                    || Integer.bitCount(width) != 1 || Integer.bitCount(height) != 1
                    || storedMip != mipLevel || storedPadding != expectedPadding
                    || count != sprites.size() || (REQUIRE_VERIFIED && !verified)) {
                return invalid();
            }

            HashMap<Identifier, Position> positions = new HashMap<>(Math.max(16, count * 4 / 3 + 1));
            HashMap<Identifier, SpriteContents> current = new HashMap<>(Math.max(16, count * 4 / 3 + 1));
            for (SpriteContents sprite : sprites) current.put(sprite.name(), sprite);
            ArrayList<Rect> rectangles = new ArrayList<>(count);
            int quantum = 1 << mipLevel;

            for (int i = 0; i < count; i++) {
                Identifier id = Identifier.parse(in.readUTF());
                int spriteWidth = in.readInt();
                int spriteHeight = in.readInt();
                int x = in.readInt();
                int y = in.readInt();
                SpriteContents live = current.get(id);
                if (live == null || live.width() != spriteWidth || live.height() != spriteHeight
                        || positions.put(id, new Position(x, y)) != null) {
                    return invalid();
                }

                int holderWidth = roundUp(spriteWidth + storedPadding * 2, quantum);
                int holderHeight = roundUp(spriteHeight + storedPadding * 2, quantum);
                if (x < 0 || y < 0 || (x & (quantum - 1)) != 0 || (y & (quantum - 1)) != 0
                        || x > width - holderWidth || y > height - holderHeight) {
                    return invalid();
                }
                rectangles.add(new Rect(id, x, y, holderWidth, holderHeight));
            }
            if (in.available() != 0 || positions.size() != count || overlaps(rectangles)) return invalid();

            ModelPipelineProfiler.addCount("atlas.layout-cache.hit", 1);
            ModelPipelineProfiler.addCount("atlas.layout-cache.verified-hit", verified ? 1 : 0);
            return new Layout(width, height, mipLevel, storedPadding, verified, Map.copyOf(positions));
        } catch (Exception failure) {
            return invalid();
        }
    }

    /**
     * Serializes the small layout record now, but defers persistent I/O until after startup/reload.
     * Unverified FastStitcher placements are deliberately not persisted while verified-cache mode is on.
     */
    public static void store(Identifier atlas, List<SpriteContents> sprites,
            int maxTextureSize, int mipLevel, int anisotropyBit,
            int width, int height, int padding, boolean verified,
            Map<Identifier, TextureAtlasSprite> result) {
        PersistentBlobCache cache = cache();
        if (cache == null || hasDuplicateNames(sprites) || result.size() != sprites.size()) return;
        if (REQUIRE_VERIFIED && !verified) {
            ModelPipelineProfiler.addCount("atlas.layout-cache.unverified-not-stored", 1);
            return;
        }
        if (padding != padding(mipLevel, anisotropyBit)) return;
        if (!validateResult(sprites, width, height, mipLevel, padding, result)) {
            ModelPipelineProfiler.addCount("atlas.layout-cache.store-invalid", 1);
            return;
        }

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
                out.writeBoolean(verified);
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

    private static Layout invalid() {
        ModelPipelineProfiler.addCount("atlas.layout-cache.invalid", 1);
        return null;
    }

    private static boolean validateResult(List<SpriteContents> sprites, int width, int height,
            int mipLevel, int padding, Map<Identifier, TextureAtlasSprite> result) {
        if (width <= 0 || height <= 0 || Integer.bitCount(width) != 1 || Integer.bitCount(height) != 1) return false;
        int quantum = 1 << mipLevel;
        ArrayList<Rect> rectangles = new ArrayList<>(sprites.size());
        for (SpriteContents sprite : sprites) {
            TextureAtlasSprite placed = result.get(sprite.name());
            if (placed == null) return false;
            int x = placed.getX();
            int y = placed.getY();
            int holderWidth = roundUp(sprite.width() + padding * 2, quantum);
            int holderHeight = roundUp(sprite.height() + padding * 2, quantum);
            if (x < 0 || y < 0 || (x & (quantum - 1)) != 0 || (y & (quantum - 1)) != 0
                    || x > width - holderWidth || y > height - holderHeight) return false;
            rectangles.add(new Rect(sprite.name(), x, y, holderWidth, holderHeight));
        }
        return !overlaps(rectangles);
    }

    /** Sweep-line overlap check for padded/rounded holder rectangles. */
    private static boolean overlaps(List<Rect> rectangles) {
        ArrayList<Rect> sorted = new ArrayList<>(rectangles);
        sorted.sort(Comparator.comparingInt(Rect::x).thenComparingInt(Rect::y));
        ArrayList<Rect> active = new ArrayList<>();
        for (Rect current : sorted) {
            active.removeIf(previous -> previous.right() <= current.x());
            for (Rect previous : active) {
                if (previous.bottom() > current.y() && current.bottom() > previous.y()) return true;
            }
            active.add(current);
        }
        return false;
    }

    private static int padding(int mipLevel, int anisotropyBit) {
        int clamped = Math.max(0, Math.min(4, anisotropyBit - 1));
        return 1 << mipLevel << clamped;
    }

    private static int roundUp(int value, int quantum) {
        return (value + quantum - 1) & -quantum;
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
        update(digest, "\0layout-policy=" + POLICY_VERSION + ':' + maxTextureSize + ':' + mipLevel + ':' + anisotropyBit + '\n');
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
                blobs = new PersistentBlobCache(environment.generationDirectory(), "atlas-layouts-v2");
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

    private record Rect(Identifier id, int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
    }

    public record Position(int x, int y) {}
    public record Layout(int width, int height, int mipLevel, int padding,
                         boolean verified, Map<Identifier, Position> positions) {}
}
