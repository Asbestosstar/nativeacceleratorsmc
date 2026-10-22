package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.device.GpuSurface;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Owns the swapchain-dependent Vulkan objects for one SDL surface.
 *
 * <p>This is deliberately below the RenderPearl surface SPI. It owns no window and never creates a
 * surface; {@link NativeVulkanOwnedWsiBootstrap} owns the instance, SDL surface, device, and queues.
 * The class is useful as the resource/lifetime boundary for the eventual {@code GpuSurfaceBackend},
 * but it does not by itself claim that acquire-render-present has succeeded.</p>
 */
final class NativeVulkanSwapchainSession implements AutoCloseable {
    private static final long ACQUIRE_TIMEOUT = Long.MAX_VALUE;

    private final NativeVulkanOwnedWsiBootstrap bootstrap;
    private final NativeVulkanDeviceState state;
    private final List<FrameSync> frames;
    private long[] renderFinishedSemaphores = new long[0];
    private long swapchain;
    private long[] images = new long[0];
    private long[] imageViews = new long[0];
    private int[] imageLayouts = new int[0];
    private int imageFormat;
    private int colorSpace;
    private int width;
    private int height;
    private GpuSurface.PresentMode presentMode;
    private int currentImage = -1;
    private int currentFrame;
    private boolean closed;

    NativeVulkanSwapchainSession(NativeVulkanOwnedWsiBootstrap bootstrap,
                                 NativeVulkanDeviceState state,
                                 int framesInFlight) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.state = Objects.requireNonNull(state, "state");
        if (framesInFlight < 1 || framesInFlight > 8) {
            throw new IllegalArgumentException("framesInFlight must be between 1 and 8");
        }
        this.frames = new ArrayList<>(framesInFlight);
        try {
            for (int i = 0; i < framesInFlight; i++) frames.add(createFrameSync());
        } catch (Throwable failure) {
            closeFrames();
            throw failure;
        }
    }

    synchronized void configure(int requestedWidth, int requestedHeight,
                                GpuSurface.PresentMode requestedMode) {
        requireOpen();
        state.requireOpen();
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            throw new IllegalArgumentException("Swapchain dimensions must be positive");
        }
        Objects.requireNonNull(requestedMode, "requestedMode");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(VK10.vkDeviceWaitIdle(bootstrap.device()), "wait for swapchain recreation");
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                    bootstrap.physicalDevice(), bootstrap.surface(), capabilities),
                    "query surface capabilities");
            VkSurfaceFormatKHR.Buffer formats = enumerateFormats(stack);
            VkSurfaceFormatKHR selectedFormat = chooseFormat(formats);
            IntBuffer modes = enumeratePresentModes(stack);
            int selectedPresentMode = choosePresentMode(modes, requestedMode);
            NativeVulkanSwapchainPolicy.Extent extent = NativeVulkanSwapchainPolicy.chooseExtent(
                    requestedWidth, requestedHeight,
                    capabilities.currentExtent().width(), capabilities.currentExtent().height(),
                    capabilities.minImageExtent().width(), capabilities.minImageExtent().height(),
                    capabilities.maxImageExtent().width(), capabilities.maxImageExtent().height());
            int imageCount = NativeVulkanSwapchainPolicy.chooseImageCount(
                    capabilities.minImageCount(), capabilities.maxImageCount(), 3);
            int requiredUsage = NativeVulkanSwapchainPolicy.requiredSwapchainUsage(capabilities.supportedUsageFlags());
            long replacement = createSwapchain(stack, capabilities, selectedFormat, selectedPresentMode,
                    extent, imageCount, requiredUsage, swapchain);
            long[] replacementImages;
            long[] replacementViews = new long[0];
            long[] replacementRenderFinishedSemaphores = new long[0];
            try {
                replacementImages = getImages(stack, replacement);
                replacementViews = createImageViews(stack, replacementImages, selectedFormat.format());
                replacementRenderFinishedSemaphores = createRenderFinishedSemaphores(stack, replacementImages.length);
            } catch (RuntimeException | LinkageError failure) {
                destroyImageViews(replacementViews);
                destroySemaphores(replacementRenderFinishedSemaphores);
                KHRSwapchain.vkDestroySwapchainKHR(bootstrap.device(), replacement, null);
                throw failure;
            }
            long old = swapchain;
            long[] oldViews = imageViews;
            long[] oldRenderFinishedSemaphores = renderFinishedSemaphores;
            swapchain = replacement;
            images = replacementImages;
            imageViews = replacementViews;
            renderFinishedSemaphores = replacementRenderFinishedSemaphores;
            imageLayouts = new int[replacementImages.length];
            java.util.Arrays.fill(imageLayouts, VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            imageFormat = selectedFormat.format();
            colorSpace = selectedFormat.colorSpace();
            width = extent.width();
            height = extent.height();
            presentMode = NativeVulkanSwapchainPolicy.toPresentMode(selectedPresentMode);
            currentImage = -1;
            destroyImageViews(oldViews);
            destroySemaphores(oldRenderFinishedSemaphores);
            if (old != 0L) KHRSwapchain.vkDestroySwapchainKHR(bootstrap.device(), old, null);
        } catch (RuntimeException | LinkageError failure) {
            if (isDeviceLost(failure)) state.markLost("swapchain configuration lost the device");
            throw failure;
        }
    }

    synchronized NativeVulkanSwapchainPolicy.Result acquireNextTexture() {
        requireReady();
        if (currentImage >= 0) throw new IllegalStateException("A swapchain image is already acquired");
        FrameSync frame = frames.get(currentFrame);
        check(VK10.vkWaitForFences(bootstrap.device(), frame.fence, true, ACQUIRE_TIMEOUT),
                "wait frame fence");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer imageIndex = stack.mallocInt(1);
            int result = KHRSwapchain.vkAcquireNextImageKHR(bootstrap.device(), swapchain,
                    ACQUIRE_TIMEOUT, frame.acquireSemaphore, 0L, imageIndex);
            NativeVulkanSwapchainPolicy.Result classified = NativeVulkanSwapchainPolicy.classify(result);
            if (classified == NativeVulkanSwapchainPolicy.Result.DEVICE_LOST) {
                state.markLost("vkAcquireNextImageKHR returned VK_ERROR_DEVICE_LOST");
            }
            if (classified == NativeVulkanSwapchainPolicy.Result.SUCCESS
                    || classified == NativeVulkanSwapchainPolicy.Result.SUBOPTIMAL) {
                currentImage = imageIndex.get(0);
                if (currentImage < 0 || currentImage >= images.length) {
                    throw new IllegalStateException("vkAcquireNextImageKHR returned invalid image index " + currentImage);
                }
            } else if (classified == NativeVulkanSwapchainPolicy.Result.FAILURE) {
                throw new IllegalStateException("vkAcquireNextImageKHR failed with Vulkan result " + result);
            }
            return classified;
        }
    }

    synchronized NativeVulkanSwapchainPolicy.Result present() {
        requireReady();
        if (currentImage < 0) throw new IllegalStateException("No swapchain image is acquired");
        FrameSync frame = frames.get(currentFrame);
        long renderFinishedSemaphore = renderFinishedSemaphores[currentImage];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPresentInfoKHR info = VkPresentInfoKHR.calloc(stack)
                    .sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(renderFinishedSemaphore))
                    .pSwapchains(stack.longs(swapchain))
                    .pImageIndices(stack.ints(currentImage));
            int result = KHRSwapchain.vkQueuePresentKHR(bootstrap.presentQueue(), info);
            NativeVulkanSwapchainPolicy.Result classified = NativeVulkanSwapchainPolicy.classify(result);
            if (classified == NativeVulkanSwapchainPolicy.Result.DEVICE_LOST) {
                state.markLost("vkQueuePresentKHR returned VK_ERROR_DEVICE_LOST");
            }
            currentImage = -1;
            currentFrame = (currentFrame + 1) % frames.size();
            return classified;
        }
    }

    synchronized long acquireSemaphore() {
        requireReady();
        return frames.get(currentFrame).acquireSemaphore;
    }

    synchronized long renderFinishedSemaphore() {
        requireReady();
        if (currentImage < 0 || currentImage >= renderFinishedSemaphores.length) {
            throw new IllegalStateException("No acquired image for a render-finished semaphore");
        }
        return renderFinishedSemaphores[currentImage];
    }

    synchronized long currentFence() {
        requireReady();
        return frames.get(currentFrame).fence;
    }

    synchronized long[] imageViews() {
        requireReady();
        return imageViews.clone();
    }

    synchronized long[] images() {
        requireReady();
        return images.clone();
    }

    synchronized long currentImageHandle() {
        requireReady();
        if (currentImage < 0 || currentImage >= images.length) {
            throw new IllegalStateException("No valid acquired swapchain image");
        }
        return images[currentImage];
    }

    synchronized int currentImageLayout() {
        requireReady();
        if (currentImage < 0 || currentImage >= imageLayouts.length) throw new IllegalStateException("No acquired image");
        return imageLayouts[currentImage];
    }

    synchronized void setCurrentImageLayout(int layout) {
        requireReady();
        if (currentImage < 0 || currentImage >= imageLayouts.length) throw new IllegalStateException("No acquired image");
        imageLayouts[currentImage] = layout;
    }

    synchronized int imageFormat() { requireReady(); return imageFormat; }
    synchronized int colorSpace() { requireReady(); return colorSpace; }
    synchronized int width() { requireReady(); return width; }
    synchronized int height() { requireReady(); return height; }
    synchronized GpuSurface.PresentMode presentMode() { requireReady(); return presentMode; }

    synchronized Collection<GpuSurface.PresentMode> supportedPresentModes() {
        requireOpen();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer modes = enumeratePresentModes(stack);
            EnumSet<GpuSurface.PresentMode> result = EnumSet.noneOf(GpuSurface.PresentMode.class);
            for (int i = 0; i < modes.remaining(); i++) {
                result.add(NativeVulkanSwapchainPolicy.toPresentMode(modes.get(i)));
            }
            return List.copyOf(result);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (state.phase() == NativeVulkanDeviceState.Phase.OPEN) {
            int idleResult;
            try {
                idleResult = VK10.vkDeviceWaitIdle(bootstrap.device());
            } catch (Throwable failure) {
                state.markLost("Could not prove device idle before swapchain teardown");
                throw new IllegalStateException("Could not prove device idle before swapchain teardown", failure);
            }
            if (idleResult != VK10.VK_SUCCESS) {
                state.markLost("vkDeviceWaitIdle failed during swapchain teardown with Vulkan result " + idleResult);
                throw new IllegalStateException("Could not prove device idle before swapchain teardown: " + idleResult);
            }
        }
        closed = true;
        currentImage = -1;
        destroyImageViews(imageViews);
        imageViews = new long[0];
        destroySemaphores(renderFinishedSemaphores);
        renderFinishedSemaphores = new long[0];
        images = new long[0];
        imageLayouts = new int[0];
        if (swapchain != 0L) {
            KHRSwapchain.vkDestroySwapchainKHR(bootstrap.device(), swapchain, null);
            swapchain = 0L;
        }
        closeFrames();
    }

    private long createSwapchain(MemoryStack stack, VkSurfaceCapabilitiesKHR capabilities,
                                 VkSurfaceFormatKHR format, int presentMode,
                                 NativeVulkanSwapchainPolicy.Extent extent, int imageCount,
                                 int usage, long oldSwapchain) {
        boolean split = bootstrap.graphicsQueueFamily() != bootstrap.presentQueueFamily();
        IntBuffer families = split ? stack.ints(bootstrap.graphicsQueueFamily(), bootstrap.presentQueueFamily()) : null;
        VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType(KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .surface(bootstrap.surface())
                .minImageCount(imageCount)
                .imageFormat(format.format())
                .imageColorSpace(format.colorSpace())
                .imageExtent(VkExtent2D.calloc(stack).set(extent.width(), extent.height()))
                .imageArrayLayers(1)
                .imageUsage(usage)
                .imageSharingMode(split ? VK10.VK_SHARING_MODE_CONCURRENT : VK10.VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(capabilities.currentTransform())
                .compositeAlpha(selectCompositeAlpha(capabilities.supportedCompositeAlpha()))
                .presentMode(presentMode)
                .clipped(true)
                .oldSwapchain(oldSwapchain);
        if (families != null) info.queueFamilyIndexCount(families.remaining()).pQueueFamilyIndices(families);
        LongBuffer output = stack.mallocLong(1);
        check(KHRSwapchain.vkCreateSwapchainKHR(bootstrap.device(), info, null, output), "create swapchain");
        return output.get(0);
    }

    private long[] getImages(MemoryStack stack, long candidate) {
        IntBuffer count = stack.ints(0);
        check(KHRSwapchain.vkGetSwapchainImagesKHR(bootstrap.device(), candidate, count, null),
                "query swapchain images");
        if (count.get(0) < 1) throw new IllegalStateException("Swapchain returned no images");
        LongBuffer handles = stack.mallocLong(count.get(0));
        check(KHRSwapchain.vkGetSwapchainImagesKHR(bootstrap.device(), candidate, count, handles),
                "get swapchain images");
        long[] result = new long[count.get(0)];
        handles.get(result);
        return result;
    }

    private long[] createImageViews(MemoryStack stack, long[] source, int format) {
        long[] result = new long[source.length];
        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
        try {
            for (int i = 0; i < source.length; i++) {
                VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(source[i]).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                        .format(format).subresourceRange(range);
                LongBuffer output = stack.mallocLong(1);
                check(VK10.vkCreateImageView(bootstrap.device(), info, null, output), "create swapchain image view");
                result[i] = output.get(0);
            }
            return result;
        } catch (RuntimeException | LinkageError failure) {
            destroyImageViews(result);
            throw failure;
        }
    }

    private VkSurfaceFormatKHR.Buffer enumerateFormats(MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                bootstrap.physicalDevice(), bootstrap.surface(), count, null), "query surface formats");
        if (count.get(0) < 1) throw new IllegalStateException("Surface returned no formats");
        VkSurfaceFormatKHR.Buffer result = VkSurfaceFormatKHR.calloc(count.get(0), stack);
        check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                bootstrap.physicalDevice(), bootstrap.surface(), count, result), "get surface formats");
        return result;
    }

    private IntBuffer enumeratePresentModes(MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        check(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(
                bootstrap.physicalDevice(), bootstrap.surface(), count, null), "query present modes");
        if (count.get(0) < 1) throw new IllegalStateException("Surface returned no present modes");
        int modeCount = count.get(0);
        IntBuffer result = stack.mallocInt(modeCount);
        check(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(
                bootstrap.physicalDevice(), bootstrap.surface(), count, result), "get present modes");
        // Vulkan writes through the buffer address; LWJGL does not advance the Java position.
        // Calling flip() here would therefore set limit to zero and make every surface appear to
        // expose no present modes.
        result.position(0).limit(Math.min(modeCount, count.get(0)));
        return result;
    }

    private static VkSurfaceFormatKHR chooseFormat(VkSurfaceFormatKHR.Buffer formats) {
        for (int i = 0; i < formats.capacity(); i++) {
            VkSurfaceFormatKHR format = formats.get(i);
            if (format.format() == VK10.VK_FORMAT_B8G8R8A8_SRGB
                    && format.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) return format;
        }
        return formats.get(0);
    }

    private static int choosePresentMode(IntBuffer modes, GpuSurface.PresentMode requested) {
        int fallback = KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
        int desired = switch (requested) {
            case IMMEDIATE -> KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
            case MAILBOX -> KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
            case FIFO -> KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
            case FIFO_RELAXED -> KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR;
        };
        for (int i = modes.position(); i < modes.limit(); i++) if (modes.get(i) == desired) return desired;
        for (int i = modes.position(); i < modes.limit(); i++) if (modes.get(i) == fallback) return fallback;
        throw new IllegalStateException("Surface does not expose FIFO present mode");
    }

    private static int selectCompositeAlpha(int supported) {
        int[] candidates = { KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
                KHRSurface.VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
                KHRSurface.VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
                KHRSurface.VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR };
        for (int candidate : candidates) if ((supported & candidate) != 0) return candidate;
        throw new IllegalStateException("Surface exposes no supported composite-alpha mode");
    }

    private FrameSync createFrameSync() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo semaphore = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            VkFenceCreateInfo fence = VkFenceCreateInfo.calloc(stack)
                    .sType$Default().flags(VK10.VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer acquire = stack.mallocLong(1);
            LongBuffer renderFinished = stack.mallocLong(1);
            LongBuffer fenceOut = stack.mallocLong(1);
            check(VK10.vkCreateSemaphore(bootstrap.device(), semaphore, null, acquire), "create acquire semaphore");
            try {
                check(VK10.vkCreateFence(bootstrap.device(), fence, null, fenceOut), "create frame fence");
                return new FrameSync(acquire.get(0), fenceOut.get(0));
            } catch (Throwable failure) {
                VK10.vkDestroySemaphore(bootstrap.device(), acquire.get(0), null);
                throw failure;
            }
        }
    }

    private long[] createRenderFinishedSemaphores(MemoryStack stack, int count) {
        VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
        long[] semaphores = new long[count];
        try {
            for (int i = 0; i < count; i++) {
                LongBuffer output = stack.mallocLong(1);
                check(VK10.vkCreateSemaphore(bootstrap.device(), info, null, output),
                        "create per-image render-finished semaphore");
                semaphores[i] = output.get(0);
            }
            return semaphores;
        } catch (RuntimeException | LinkageError failure) {
            destroySemaphores(semaphores);
            throw failure;
        }
    }

    private void destroySemaphores(long[] semaphores) {
        for (long semaphore : semaphores) {
            if (semaphore != 0L) VK10.vkDestroySemaphore(bootstrap.device(), semaphore, null);
        }
    }

    private void closeFrames() {
        for (FrameSync frame : frames) {
            try { VK10.vkDestroyFence(bootstrap.device(), frame.fence, null); } catch (Throwable ignored) { }
            try { VK10.vkDestroySemaphore(bootstrap.device(), frame.acquireSemaphore, null); } catch (Throwable ignored) { }
        }
        frames.clear();
    }

    private void destroyImageViews(long[] views) {
        for (long view : views) if (view != 0L) VK10.vkDestroyImageView(bootstrap.device(), view, null);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Vulkan swapchain session is closed");
    }

    private void requireReady() {
        requireOpen();
        state.requireOpen();
        if (swapchain == 0L) throw new IllegalStateException("Vulkan swapchain is not configured");
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException(operation + " failed with Vulkan result " + result);
    }

    private static boolean isDeviceLost(Throwable failure) {
        String message = failure.getMessage();
        return message != null && message.endsWith("Vulkan result " + VK10.VK_ERROR_DEVICE_LOST);
    }

    private record FrameSync(long acquireSemaphore, long fence) { }
}
