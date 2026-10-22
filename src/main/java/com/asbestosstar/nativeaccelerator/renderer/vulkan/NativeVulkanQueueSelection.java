package com.asbestosstar.nativeaccelerator.renderer.vulkan;

/** Pure queue-family suitability policy for the first RenderPearl Vulkan adapter slice. */
final class NativeVulkanQueueSelection {
    static final int GRAPHICS_BIT = 0x00000001;
    static final int COMPUTE_BIT = 0x00000002;

    private NativeVulkanQueueSelection() {
    }

    static Selection select(int[] queueFlags, int[] queueCounts, boolean[] presentSupport) {
        if (queueFlags == null || queueCounts == null || presentSupport == null
                || queueFlags.length != queueCounts.length
                || queueFlags.length != presentSupport.length) {
            throw new IllegalArgumentException("Queue-family evidence arrays must have equal lengths");
        }
        int bestCombined = -1;
        int bestCombinedScore = Integer.MIN_VALUE;
        int bestGraphics = -1;
        int bestPresent = -1;
        int bestSplitScore = Integer.MIN_VALUE;
        for (int index = 0; index < queueFlags.length; index++) {
            if (queueCounts[index] <= 0) continue;
            boolean graphics = (queueFlags[index] & GRAPHICS_BIT) != 0;
            boolean compute = (queueFlags[index] & COMPUTE_BIT) != 0;
            if (graphics && compute && presentSupport[index]) {
                int score = Integer.bitCount(queueFlags[index]) * 100 + queueCounts[index];
                if (bestCombined < 0 || score > bestCombinedScore) {
                    bestCombined = index;
                    bestCombinedScore = score;
                }
            }
            if (graphics && compute) {
                for (int present = 0; present < queueFlags.length; present++) {
                    if (queueCounts[present] > 0 && presentSupport[present]) {
                        int score = Integer.bitCount(queueFlags[index]) * 100
                                + Integer.bitCount(queueFlags[present]) * 10
                                + Math.min(queueCounts[index], queueCounts[present]);
                        if (bestGraphics < 0 || score > bestSplitScore) {
                            bestGraphics = index;
                            bestPresent = present;
                            bestSplitScore = score;
                        }
                    }
                }
            }
        }
        if (bestCombined >= 0) {
            return new Selection(bestCombined, bestCombined, queueCounts[bestCombined]);
        }
        if (bestGraphics >= 0) {
            return new Selection(bestGraphics, bestPresent,
                    Math.min(queueCounts[bestGraphics], queueCounts[bestPresent]));
        }
        throw new IllegalArgumentException(
                "No queue family provides graphics, compute, and presentation support");
    }

    record Selection(int graphicsFamily, int presentFamily, int queueCount) {
        Selection {
            if (graphicsFamily < 0 || presentFamily < 0 || queueCount < 1) {
                throw new IllegalArgumentException("Invalid Vulkan queue selection");
            }
        }

        boolean combined() {
            return graphicsFamily == presentFamily;
        }
    }
}
