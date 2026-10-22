package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.device.GpuSurface;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Pure swapchain decisions and Vulkan result classification used by the WSI implementation. */
final class NativeVulkanSwapchainPolicy {
    private NativeVulkanSwapchainPolicy() {
    }

    static Extent chooseExtent(int requestedWidth, int requestedHeight,
                               int currentWidth, int currentHeight,
                               int minWidth, int minHeight,
                               int maxWidth, int maxHeight) {
        if (requestedWidth < 0 || requestedHeight < 0 || minWidth < 0 || minHeight < 0
                || maxWidth < minWidth || maxHeight < minHeight) {
            throw new IllegalArgumentException("Invalid Vulkan surface extent evidence");
        }
        if (currentWidth != Integer.MAX_VALUE && currentHeight != Integer.MAX_VALUE) {
            if (currentWidth <= 0 || currentHeight <= 0) {
                throw new IllegalArgumentException("Surface reported a non-positive fixed extent");
            }
            return new Extent(currentWidth, currentHeight);
        }
        if (maxWidth == 0 || maxHeight == 0) {
            throw new IllegalArgumentException("Surface reported an invalid maximum extent");
        }
        int width = clamp(requestedWidth, minWidth, maxWidth);
        int height = clamp(requestedHeight, minHeight, maxHeight);
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Swapchain extent is zero");
        return new Extent(width, height);
    }

    static int chooseImageCount(int minImageCount, int maxImageCount, int preferredImageCount) {
        if (minImageCount < 1 || maxImageCount < 0 || (maxImageCount != 0 && maxImageCount < minImageCount)
                || preferredImageCount < 1) {
            throw new IllegalArgumentException("Invalid Vulkan image-count evidence");
        }
        return Math.max(minImageCount,
                maxImageCount == 0 ? preferredImageCount : Math.min(maxImageCount, preferredImageCount));
    }

    static int requiredSwapchainUsage(int supportedUsageFlags) {
        int required = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if ((supportedUsageFlags & required) != required) {
            throw new IllegalArgumentException(
                    "Surface does not support required color-attachment and transfer-destination usage");
        }
        return required;
    }

    static GpuSurface.PresentMode toPresentMode(int vulkanMode) {
        return switch (vulkanMode) {
            case KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR -> GpuSurface.PresentMode.IMMEDIATE;
            case KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR -> GpuSurface.PresentMode.MAILBOX;
            case KHRSurface.VK_PRESENT_MODE_FIFO_KHR -> GpuSurface.PresentMode.FIFO;
            case KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR -> GpuSurface.PresentMode.FIFO_RELAXED;
            default -> throw new IllegalArgumentException("Unsupported Vulkan present mode: " + vulkanMode);
        };
    }

    static Set<GpuSurface.PresentMode> presentModes(int[] vulkanModes) {
        Objects.requireNonNull(vulkanModes, "vulkanModes");
        EnumSet<GpuSurface.PresentMode> modes = EnumSet.noneOf(GpuSurface.PresentMode.class);
        for (int mode : vulkanModes) modes.add(toPresentMode(mode));
        if (modes.isEmpty()) throw new IllegalArgumentException("Surface reported no present modes");
        return Set.copyOf(modes);
    }

    static Result classify(int result) {
        if (result == VK10.VK_SUCCESS) return Result.SUCCESS;
        if (result == KHRSwapchain.VK_SUBOPTIMAL_KHR) return Result.SUBOPTIMAL;
        if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) return Result.OUT_OF_DATE;
        if (result == KHRSurface.VK_ERROR_SURFACE_LOST_KHR) return Result.SURFACE_LOST;
        if (result == VK10.VK_ERROR_DEVICE_LOST) return Result.DEVICE_LOST;
        return Result.FAILURE;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    record Extent(int width, int height) {
        Extent {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Extent must be positive");
        }
    }

    enum Result {
        SUCCESS,
        SUBOPTIMAL,
        OUT_OF_DATE,
        SURFACE_LOST,
        DEVICE_LOST,
        FAILURE
    }
}
