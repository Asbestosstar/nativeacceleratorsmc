package com.asbestosstar.nativeaccelerator.client;

import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.StitcherException;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Placement-compatible high-throughput replacement for Minecraft's {@link Stitcher}.
 *
 * <p>The packing policy is deliberately the same as vanilla 26.3: identical holder dimensions,
 * sort order, first-fit traversal order, split order, power-of-two atlas growth, padding and
 * gather coordinates. The optimization is in the search. Each region maintains conservative
 * summaries of the free rectangles below it, so a request can reject an entire fragmented branch
 * without recursively visiting every occupied slot.</p>
 *
 * <p>The summary is conservative: it may occasionally descend into a subtree that cannot fit the
 * sprite, but it never skips a subtree that vanilla could use. Therefore the first successful leaf
 * remains the same leaf vanilla would select.</p>
 */
public final class FastStitcher<T extends Stitcher.Entry> extends Stitcher<T> {
    private static final Comparator<FastHolder<?>> HOLDER_COMPARATOR = Comparator
            .<FastHolder<?>>comparingInt(holder -> -holder.height)
            .thenComparingInt(holder -> -holder.width)
            .thenComparing(holder -> holder.entry.name());

    private final int mipLevel;
    private final int maxWidth;
    private final int maxHeight;
    private final int padding;
    private final ArrayList<FastHolder<T>> texturesToBeStitched = new ArrayList<>();
    private final ArrayList<FastRegion<T>> storage = new ArrayList<>();
    private int storageX;
    private int storageY;

    public FastStitcher(int maxWidth, int maxHeight, int mipLevel, int anisotropyBit) {
        super(maxWidth, maxHeight, mipLevel, anisotropyBit);
        this.mipLevel = mipLevel;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.padding = 1 << mipLevel << Mth.clamp(anisotropyBit - 1, 0, 4);
    }

    @Override
    public int getWidth() {
        return this.storageX;
    }

    @Override
    public int getHeight() {
        return this.storageY;
    }

    @Override
    public void registerSprite(T entry) {
        int paddedWidth = entry.width() + this.padding * 2;
        int paddedHeight = entry.height() + this.padding * 2;
        this.texturesToBeStitched.add(new FastHolder<>(entry,
                smallestFittingMinTexel(paddedWidth, this.mipLevel),
                smallestFittingMinTexel(paddedHeight, this.mipLevel)));
    }

    @Override
    public void stitch() {
        ArrayList<FastHolder<T>> holders = new ArrayList<>(this.texturesToBeStitched);
        holders.sort((left, right) -> HOLDER_COMPARATOR.compare(left, right));

        for (FastHolder<T> holder : holders) {
            if (this.addToStorage(holder)) continue;
            if (this.expand(holder)) continue;

            ArrayList<Stitcher.Entry> all = new ArrayList<>(holders.size());
            for (FastHolder<T> candidate : holders) all.add(candidate.entry);
            throw new StitcherException(holder.entry, all);
        }
    }

    @Override
    public void gatherSprites(Stitcher.SpriteLoader<T> loader) {
        for (FastRegion<T> topRegion : this.storage) {
            topRegion.walk(loader, this.padding);
        }
    }

    private boolean addToStorage(FastHolder<T> holder) {
        for (FastRegion<T> region : this.storage) {
            if (region.add(holder)) return true;
        }
        return false;
    }

    /** Exact copy of vanilla's atlas-growth decision with the optimized region implementation. */
    private boolean expand(FastHolder<T> holder) {
        int xCurrentSize = Mth.smallestEncompassingPowerOfTwo(this.storageX);
        int yCurrentSize = Mth.smallestEncompassingPowerOfTwo(this.storageY);
        int xNewSize = Mth.smallestEncompassingPowerOfTwo(this.storageX + holder.width);
        int yNewSize = Mth.smallestEncompassingPowerOfTwo(this.storageY + holder.height);
        boolean xCanGrow = xNewSize <= this.maxWidth;
        boolean yCanGrow = yNewSize <= this.maxHeight;
        if (!xCanGrow && !yCanGrow) return false;

        boolean xWillGrow = xCanGrow && xCurrentSize != xNewSize;
        boolean yWillGrow = yCanGrow && yCurrentSize != yNewSize;
        boolean growOnX = xWillGrow ^ yWillGrow ? xWillGrow : xCanGrow && xCurrentSize <= yCurrentSize;

        FastRegion<T> slot;
        if (growOnX) {
            if (this.storageY == 0) this.storageY = yNewSize;
            slot = new FastRegion<>(this.storageX, 0, xNewSize - this.storageX, this.storageY);
            this.storageX = xNewSize;
        } else {
            slot = new FastRegion<>(0, this.storageY, this.storageX, yNewSize - this.storageY);
            this.storageY = yNewSize;
        }

        // Vanilla intentionally ignores Region.add(...) here and records the expanded slot regardless.
        // Preserve that edge-case behavior as well as ordinary successful placement.
        slot.add(holder);
        this.storage.add(slot);
        return true;
    }

    private static int smallestFittingMinTexel(int input, int maxMipLevel) {
        // Keep the exact bit-level rounding form used by Minecraft's Stitcher. Besides avoiding a
        // division in this very hot registration loop, it also avoids introducing a second semantic
        // definition of mip alignment into the fast packer.
        int quantum = 1 << maxMipLevel;
        return (input + quantum - 1) & -quantum;
    }

    private record FastHolder<T extends Stitcher.Entry>(T entry, int width, int height) {}

    /**
     * Vanilla-compatible guillotine region with free-space summaries.
     *
     * <p>{@code maxFreeWidth}, {@code maxFreeHeight} and {@code maxFreeArea} are necessary-fit bounds.
     * If any bound fails, no descendant can fit and the entire subtree is skipped. They are not used
     * to choose a different child: children are still visited in vanilla's original order.</p>
     */
    private static final class FastRegion<T extends Stitcher.Entry> {
        private final int originX;
        private final int originY;
        private final int width;
        private final int height;
        private FastHolder<T> holder;
        private ArrayList<FastRegion<T>> subSlots;
        private int maxFreeWidth;
        private int maxFreeHeight;
        private long maxFreeArea;

        FastRegion(int originX, int originY, int width, int height) {
            this.originX = originX;
            this.originY = originY;
            this.width = width;
            this.height = height;
            this.maxFreeWidth = width;
            this.maxFreeHeight = height;
            this.maxFreeArea = (long) width * height;
        }

        boolean add(FastHolder<T> candidate) {
            if (!this.mightFit(candidate.width, candidate.height)) return false;
            if (this.holder != null) return false;
            if (candidate.width > this.width || candidate.height > this.height) return false;

            if (candidate.width == this.width && candidate.height == this.height) {
                this.holder = candidate;
                this.clearFreeSummary();
                return true;
            }

            if (this.subSlots == null) {
                this.splitAndOccupy(candidate);
                return true;
            }

            for (FastRegion<T> subSlot : this.subSlots) {
                if (!subSlot.add(candidate)) continue;
                this.recomputeFreeSummary();
                return true;
            }
            return false;
        }

        private boolean mightFit(int requestedWidth, int requestedHeight) {
            return this.maxFreeWidth >= requestedWidth
                    && this.maxFreeHeight >= requestedHeight
                    && this.maxFreeArea >= (long) requestedWidth * requestedHeight;
        }

        /** Same child creation order as vanilla Region.add(...). */
        private void splitAndOccupy(FastHolder<T> candidate) {
            this.subSlots = new ArrayList<>(3);

            FastRegion<T> occupied = new FastRegion<>(this.originX, this.originY,
                    candidate.width, candidate.height);
            occupied.holder = candidate;
            occupied.clearFreeSummary();
            this.subSlots.add(occupied);

            int spareWidth = this.width - candidate.width;
            int spareHeight = this.height - candidate.height;
            if (spareHeight > 0 && spareWidth > 0) {
                int right = Math.max(this.height, spareWidth);
                int bottom = Math.max(this.width, spareHeight);
                if (right >= bottom) {
                    this.subSlots.add(new FastRegion<>(this.originX, this.originY + candidate.height,
                            candidate.width, spareHeight));
                    this.subSlots.add(new FastRegion<>(this.originX + candidate.width, this.originY,
                            spareWidth, this.height));
                } else {
                    this.subSlots.add(new FastRegion<>(this.originX + candidate.width, this.originY,
                            spareWidth, candidate.height));
                    this.subSlots.add(new FastRegion<>(this.originX, this.originY + candidate.height,
                            this.width, spareHeight));
                }
            } else if (spareWidth == 0) {
                this.subSlots.add(new FastRegion<>(this.originX, this.originY + candidate.height,
                        candidate.width, spareHeight));
            } else if (spareHeight == 0) {
                this.subSlots.add(new FastRegion<>(this.originX + candidate.width, this.originY,
                        spareWidth, candidate.height));
            }
            this.recomputeFreeSummary();
        }

        private void clearFreeSummary() {
            this.maxFreeWidth = 0;
            this.maxFreeHeight = 0;
            this.maxFreeArea = 0L;
        }

        private void recomputeFreeSummary() {
            int width = 0;
            int height = 0;
            long area = 0L;
            if (this.subSlots != null) {
                for (FastRegion<T> child : this.subSlots) {
                    width = Math.max(width, child.maxFreeWidth);
                    height = Math.max(height, child.maxFreeHeight);
                    area = Math.max(area, child.maxFreeArea);
                }
            } else if (this.holder == null) {
                width = this.width;
                height = this.height;
                area = (long) this.width * this.height;
            }
            this.maxFreeWidth = width;
            this.maxFreeHeight = height;
            this.maxFreeArea = area;
        }

        void walk(Stitcher.SpriteLoader<T> output, int padding) {
            if (this.holder != null) {
                output.load(this.holder.entry, this.originX, this.originY, padding);
                return;
            }
            if (this.subSlots == null) return;
            for (FastRegion<T> child : this.subSlots) child.walk(output, padding);
        }
    }
}

