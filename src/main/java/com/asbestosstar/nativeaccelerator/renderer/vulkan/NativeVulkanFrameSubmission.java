package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns one graphics command pool/buffer and submits it through a swapchain frame slot. */
final class NativeVulkanFrameSubmission implements AutoCloseable {
    private final NativeVulkanOwnedWsiBootstrap bootstrap;
    private final NativeVulkanDeviceState state;
    private final NativeVulkanSwapchainSession swapchain;
    private final long commandPool;
    private final VkCommandBuffer commandBuffer;
    private boolean recording;
    private long submittedFence;
    private boolean submitMayBeInFlight;
    private final List<Runnable> retirements = new ArrayList<>();
    private boolean closed;

    NativeVulkanFrameSubmission(NativeVulkanOwnedWsiBootstrap bootstrap,
                                NativeVulkanDeviceState state,
                                NativeVulkanSwapchainSession swapchain) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.state = Objects.requireNonNull(state, "state");
        this.swapchain = Objects.requireNonNull(swapchain, "swapchain");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(bootstrap.graphicsQueueFamily());
            LongBuffer poolOut = stack.mallocLong(1);
            check(VK10.vkCreateCommandPool(bootstrap.device(), poolInfo, null, poolOut), "create command pool");
            commandPool = poolOut.get(0);
            try {
                VkCommandBufferAllocateInfo allocation = VkCommandBufferAllocateInfo.calloc(stack)
                        .sType$Default().commandPool(commandPool)
                        .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
                PointerBuffer bufferOut = stack.mallocPointer(1);
                check(VK10.vkAllocateCommandBuffers(bootstrap.device(), allocation, bufferOut),
                        "allocate command buffer");
                commandBuffer = new VkCommandBuffer(bufferOut.get(0), bootstrap.device());
            } catch (Throwable failure) {
                VK10.vkDestroyCommandPool(bootstrap.device(), commandPool, null);
                throw failure;
            }
        }
    }

    synchronized VkCommandBuffer begin() {
        requireOpen();
        state.requireOpen();
        if (recording) throw new IllegalStateException("Frame command buffer is already recording");
        // This encoder owns one command buffer shared by all frame slots, so the slot fence alone
        // cannot prove that its previous submission has completed.
        waitForCompletion();
        check(VK10.vkResetCommandBuffer(commandBuffer, 0), "reset command buffer");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo info = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            check(VK10.vkBeginCommandBuffer(commandBuffer, info), "begin command buffer");
        }
        recording = true;
        return commandBuffer;
    }

    synchronized void endAndSubmit() {
        requireOpen();
        state.requireOpen();
        if (!recording) throw new IllegalStateException("Frame command buffer is not recording");
        check(VK10.vkEndCommandBuffer(commandBuffer), "end command buffer");
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                        .sType$Default()
                        .pWaitSemaphores(stack.longs(swapchain.acquireSemaphore()))
                        .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_TRANSFER_BIT
                                | VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                        .pCommandBuffers(stack.pointers(commandBuffer.address()))
                        .pSignalSemaphores(stack.longs(swapchain.renderFinishedSemaphore()));
                long fence = swapchain.currentFence();
                check(VK10.vkResetFences(bootstrap.device(), fence), "reset frame fence");
                submittedFence = fence;
                submitMayBeInFlight = true;
                int result = VK10.vkQueueSubmit(bootstrap.graphicsQueue(), submit, fence);
                if (result == VK10.VK_ERROR_DEVICE_LOST) {
                    state.markLost("vkQueueSubmit returned VK_ERROR_DEVICE_LOST");
                } else if (result != VK10.VK_SUCCESS) {
                    state.markLost("vkQueueSubmit failed with Vulkan result " + result
                            + " after resetting the frame fence");
                }
                check(result, "submit frame command buffer");
            }
        } finally {
            // Once vkEndCommandBuffer succeeds, this recording cannot be ended again. If submit
            // fails, leave the command buffer in a known non-recording state so close/recovery does
            // not issue a second vkEndCommandBuffer against it.
            recording = false;
        }
    }

    synchronized long submittedFence() {
        return submittedFence;
    }

    synchronized void waitForCompletion() {
        requireOpen();
        state.requireOpen();
        if (recording) throw new IllegalStateException("Cannot wait for a recording frame command buffer");
        if (submittedFence == 0L) return;
        check(VK10.vkWaitForFences(bootstrap.device(), submittedFence, true, Long.MAX_VALUE),
                "wait for submitted frame");
        submitMayBeInFlight = false;
        drainRetirements();
    }

    synchronized void retire(Runnable retirement) {
        Objects.requireNonNull(retirement, "retirement");
        if (closed) throw new IllegalStateException("Frame submission is closed");
        if (!recording && submittedFence == 0L && !submitMayBeInFlight) {
            retirement.run();
            return;
        }
        retirements.add(retirement);
    }

    private void drainRetirements() {
        RuntimeException failure = null;
        for (Runnable retirement : retirements) {
            try {
                retirement.run();
            } catch (RuntimeException thrown) {
                if (failure == null) failure = thrown;
                else failure.addSuppressed(thrown);
            }
        }
        retirements.clear();
        if (failure != null) throw failure;
    }

    synchronized void presentSubmittedFrame() {
        requireOpen();
        if (recording) throw new IllegalStateException("Cannot present before frame submission");
        if (submittedFence == 0L) throw new IllegalStateException("No submitted frame is ready to present");
    }

    synchronized VkCommandBuffer commandBuffer() {
        requireOpen();
        return commandBuffer;
    }

    synchronized boolean isRecording() { return recording; }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (recording) {
            try { VK10.vkEndCommandBuffer(commandBuffer); } catch (Throwable ignored) { }
            recording = false;
        }
        if (submittedFence != 0L) {
            try {
                if (state.phase() == NativeVulkanDeviceState.Phase.OPEN) {
                    check(VK10.vkWaitForFences(bootstrap.device(), submittedFence, true, Long.MAX_VALUE),
                            "wait for frame before command-pool destruction");
                } else if (submitMayBeInFlight) {
                    check(VK10.vkDeviceWaitIdle(bootstrap.device()),
                            "wait for device before command-pool destruction");
                }
                submittedFence = 0L;
                submitMayBeInFlight = false;
                drainRetirements();
            } catch (Throwable failure) {
                state.markLost("Could not prove frame completion before command-pool destruction");
                throw failure;
            }
        } else if (submitMayBeInFlight) {
            try {
                check(VK10.vkDeviceWaitIdle(bootstrap.device()),
                        "wait for device after uncertain frame submission");
                submitMayBeInFlight = false;
                drainRetirements();
            } catch (Throwable failure) {
                state.markLost("Could not prove frame completion before command-pool destruction");
                throw failure;
            }
        } else {
            drainRetirements();
        }
        closed = true;
        VK10.vkDestroyCommandPool(bootstrap.device(), commandPool, null);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Frame submission is closed");
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException(operation + " failed with Vulkan result " + result);
    }
}
