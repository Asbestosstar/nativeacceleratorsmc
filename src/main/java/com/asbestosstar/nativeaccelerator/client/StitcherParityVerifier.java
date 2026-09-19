package com.asbestosstar.nativeaccelerator.client;

import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Expensive opt-in verifier used to prove FastStitcher placement parity against vanilla in a real client. */
public final class StitcherParityVerifier {
    private StitcherParityVerifier() {}

    public static <T extends Stitcher.Entry> boolean equivalent(Stitcher<T> accelerated, Stitcher<T> vanilla) {
        if (accelerated.getWidth() != vanilla.getWidth() || accelerated.getHeight() != vanilla.getHeight()) {
            return false;
        }
        return placements(accelerated).equals(placements(vanilla));
    }

    private static <T extends Stitcher.Entry> List<Placement> placements(Stitcher<T> stitcher) {
        ArrayList<Placement> result = new ArrayList<>();
        stitcher.gatherSprites((entry, x, y, padding) ->
                result.add(new Placement(entry.name(), x, y, padding)));
        return result;
    }

    private record Placement(Identifier id, int x, int y, int padding) {}
}

