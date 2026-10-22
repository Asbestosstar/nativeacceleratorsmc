package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.device.GpuSurface;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;

/** Standalone, opt-in WSI probe. Never invoked inside Minecraft's SDL lifecycle. */
public final class NativeVulkanSdlSmoke {
    public static void main(String[] args) throws Exception {
        long window = 0L;
        boolean video = false;
        boolean loader = false;
        String stage = "SDL video";
        try {
            evidence("starting " + stage);
            if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO)) throw new IllegalStateException(SDLError.SDL_GetError());
            video = true;
            stage = "SDL Vulkan loader";
            evidence("starting " + stage);
            String vulkanLibrary = System.getProperty("nativeaccelerator.vulkan.sdlLibrary");
            if (vulkanLibrary == null || vulkanLibrary.isBlank()) {
                vulkanLibrary = System.getProperty("org.lwjgl.librarypath");
                if (vulkanLibrary != null && !vulkanLibrary.isBlank()
                        && System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
                    vulkanLibrary = java.nio.file.Path.of(vulkanLibrary, "libMoltenVK.dylib").toString();
                } else {
                    vulkanLibrary = null;
                }
            }
            if (!SDLVulkan.SDL_Vulkan_LoadLibrary(vulkanLibrary)) {
                throw new IllegalStateException(SDLError.SDL_GetError());
            }
            loader = true;
            stage = "visible SDL window";
            evidence("starting " + stage);
            window = SDLVideo.SDL_CreateWindow("Native Accelerator Vulkan probe", 640, 480, SDLVideo.SDL_WINDOW_VULKAN);
            if (window == 0L) throw new IllegalStateException(SDLError.SDL_GetError());
            stage = "instance/surface/device/queues";
            evidence("starting " + stage);
            try (NativeVulkanOwnedWsiBootstrap wsi = NativeVulkanOwnedWsiBootstrap.create(null, window)) {
                NativeVulkanDeviceState state = new NativeVulkanDeviceState();
                try (NativeVulkanSwapchainSession swapchain = new NativeVulkanSwapchainSession(wsi, state, 2);
                     NativeVulkanFrameSubmission submission = new NativeVulkanFrameSubmission(wsi, state, swapchain)) {
                    stage = "swapchain";
                    evidence("starting " + stage);
                    swapchain.configure(640, 480, GpuSurface.PresentMode.FIFO);
                    stage = "acquire/clear/submit/present";
                    evidence("starting " + stage);
                    for (int i = 0; i < 4; i++) presentClear(swapchain, submission);
                    stage = "resize/recreate";
                    evidence("starting " + stage);
                    submission.waitForCompletion();
                    if (!SDLVideo.SDL_SetWindowSize(window, 800, 600)) throw new IllegalStateException(SDLError.SDL_GetError());
                    swapchain.configure(800, 600, GpuSurface.PresentMode.FIFO);
                    for (int i = 0; i < 4; i++) presentClear(swapchain, submission);
                    submission.waitForCompletion();
                    stage = "Vulkan teardown";
                    evidence("starting " + stage);
                }
            }
        } catch (Throwable failure) {
            System.err.println("SDL-VULKAN: FAIL at " + stage + ": " + failure);
            throw failure;
        } finally {
            if (window != 0L) SDLVideo.SDL_DestroyWindow(window);
            if (loader) SDLVulkan.SDL_Vulkan_UnloadLibrary();
            if (video) SDLInit.SDL_Quit();
        }
        System.out.println("SDL-VULKAN: PASS (clear/present/resize/teardown only; not complete RenderPearl readiness)");
    }

    private static void presentClear(NativeVulkanSwapchainSession swapchain, NativeVulkanFrameSubmission submission) {
        var acquired = swapchain.acquireNextTexture();
        if (acquired != NativeVulkanSwapchainPolicy.Result.SUCCESS
                && acquired != NativeVulkanSwapchainPolicy.Result.SUBOPTIMAL) {
            throw new IllegalStateException("Acquire returned " + acquired);
        }
        presentAcquiredClear(swapchain, submission);
    }

    private static void presentAcquiredClear(NativeVulkanSwapchainSession swapchain,
                                             NativeVulkanFrameSubmission submission) {
        var command = submission.begin();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType$Default().oldLayout(swapchain.currentImageLayout())
                    .newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .image(swapchain.currentImageHandle()).subresourceRange(range);
            VK10.vkCmdPipelineBarrier(command, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);
            VkClearColorValue color = VkClearColorValue.calloc(stack)
                    .float32(0, 0.1f).float32(1, 0.3f).float32(2, 0.6f).float32(3, 1.0f);
            VK10.vkCmdClearColorImage(command, swapchain.currentImageHandle(),
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, range);
            barrier.oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(0);
            VK10.vkCmdPipelineBarrier(command, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, barrier);
            swapchain.setCurrentImageLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        }
        submission.endAndSubmit();
        var result = swapchain.present();
        submission.presentSubmittedFrame();
        if (result != NativeVulkanSwapchainPolicy.Result.SUCCESS
                && result != NativeVulkanSwapchainPolicy.Result.SUBOPTIMAL) {
            throw new IllegalStateException("Present returned " + result);
        }
    }

    private static void evidence(String message) {
        System.out.println("SDL-VULKAN: " + message);
        System.out.flush();
    }
}
