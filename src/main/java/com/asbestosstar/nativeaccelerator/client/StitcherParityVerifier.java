package com.asbestosstar.nativeaccelerator.client;

import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Correctness verifier for FastStitcher and persistent atlas placements. */
public final class StitcherParityVerifier {
    private StitcherParityVerifier() {}

    public static <T extends Stitcher.Entry> boolean equivalent(Stitcher<T> accelerated, Stitcher<T> vanilla) {
        return mismatch(accelerated, vanilla, 0) == null;
    }

    /** Returns null on exact placement parity, otherwise a compact diagnostic describing the first mismatch. */
    public static <T extends Stitcher.Entry> String mismatch(
            Stitcher<T> accelerated, Stitcher<T> vanilla, int mipLevel) {
        String fastInvalid = validationError(accelerated, mipLevel);
        if (fastInvalid != null) return "FastStitcher invalid: " + fastInvalid;
        String vanillaInvalid = validationError(vanilla, mipLevel);
        if (vanillaInvalid != null) return "vanilla Stitcher unexpectedly invalid: " + vanillaInvalid;
        if (accelerated.getWidth() != vanilla.getWidth() || accelerated.getHeight() != vanilla.getHeight()) {
            return "atlas size fast=" + accelerated.getWidth() + 'x' + accelerated.getHeight()
                    + " vanilla=" + vanilla.getWidth() + 'x' + vanilla.getHeight();
        }
        Map<Identifier, Placement> fast = placements(accelerated);
        Map<Identifier, Placement> reference = placements(vanilla);
        if (fast.size() != reference.size()) {
            return "sprite count fast=" + fast.size() + " vanilla=" + reference.size();
        }
        for (Map.Entry<Identifier, Placement> entry : reference.entrySet()) {
            Placement got = fast.get(entry.getKey());
            if (got == null) return "missing sprite " + entry.getKey();
            if (!got.equals(entry.getValue())) {
                return entry.getKey() + " fast=" + got + " vanilla=" + entry.getValue();
            }
        }
        return null;
    }

    /** Structural validation independent of vanilla: bounds, mip alignment and padded-holder overlap. */
    public static <T extends Stitcher.Entry> String validationError(Stitcher<T> stitcher, int mipLevel) {
        int width = stitcher.getWidth();
        int height = stitcher.getHeight();
        if (width <= 0 || height <= 0) return "non-positive atlas size " + width + 'x' + height;
        if (Integer.bitCount(width) != 1 || Integer.bitCount(height) != 1) {
            return "atlas dimensions are not powers of two: " + width + 'x' + height;
        }
        int quantum = 1 << mipLevel;
        ArrayList<Rect> rects = new ArrayList<>();
        LinkedHashMap<Identifier, Placement> seen = new LinkedHashMap<>();
        final String[] error = {null};
        stitcher.gatherSprites((entry, x, y, padding) -> {
            if (error[0] != null) return;
            Placement placement = new Placement(x, y, padding);
            if (seen.put(entry.name(), placement) != null) {
                error[0] = "duplicate sprite " + entry.name();
                return;
            }
            int holderWidth = roundUp(entry.width() + padding * 2, quantum);
            int holderHeight = roundUp(entry.height() + padding * 2, quantum);
            if (x < 0 || y < 0 || (x & (quantum - 1)) != 0 || (y & (quantum - 1)) != 0
                    || x > width - holderWidth || y > height - holderHeight) {
                error[0] = entry.name() + " out of bounds/alignment at " + x + ',' + y
                        + " holder=" + holderWidth + 'x' + holderHeight + " atlas=" + width + 'x' + height;
                return;
            }
            rects.add(new Rect(entry.name(), x, y, holderWidth, holderHeight));
        });
        if (error[0] != null) return error[0];

        rects.sort(Comparator.comparingInt(Rect::x).thenComparingInt(Rect::y));
        ArrayList<Rect> active = new ArrayList<>();
        for (Rect current : rects) {
            active.removeIf(previous -> previous.right() <= current.x());
            for (Rect previous : active) {
                if (previous.bottom() > current.y() && current.bottom() > previous.y()) {
                    return "padded holders overlap: " + previous.id() + " and " + current.id();
                }
            }
            active.add(current);
        }
        return null;
    }

    private static <T extends Stitcher.Entry> Map<Identifier, Placement> placements(Stitcher<T> stitcher) {
        LinkedHashMap<Identifier, Placement> result = new LinkedHashMap<>();
        stitcher.gatherSprites((entry, x, y, padding) ->
                result.put(entry.name(), new Placement(x, y, padding)));
        return result;
    }

    private static int roundUp(int value, int quantum) {
        return (value + quantum - 1) & -quantum;
    }

    private record Rect(Identifier id, int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
    }

    private record Placement(int x, int y, int padding) {}
}

