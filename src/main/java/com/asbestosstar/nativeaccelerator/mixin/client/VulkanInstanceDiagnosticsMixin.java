package com.asbestosstar.nativeaccelerator.mixin.client;

import com.asbestosstar.nativeaccelerator.renderer.VulkanInstanceExtensionDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

/** Log-only observation of RenderPearl's exact instance-extension request. */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanInstance")
public abstract class VulkanInstanceDiagnosticsMixin {
    @Redirect(
            method = "<init>(IZZ)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"),
            require = 0)
    private int nativeaccelerator$logInstanceExtensions(VkInstanceCreateInfo createInfo,
                                                         org.lwjgl.vulkan.VkAllocationCallbacks allocator,
                                                         PointerBuffer instance) {
        if (!Boolean.parseBoolean(System.getProperty(
                "nativeaccelerator.renderer.vulkan.instanceDiagnostics", "true"))) {
            return VK12.vkCreateInstance(createInfo, allocator, instance);
        }
        org.lwjgl.system.MemoryStack stack;
        try {
            stack = org.lwjgl.system.MemoryStack.stackPush();
        } catch (Throwable ignored) {
            return VK12.vkCreateInstance(createInfo, allocator, instance);
        }
        try {
            try {
                boolean repaired = VulkanInstanceExtensionDiagnostics.repairTruncatedExtensions(createInfo, stack);
                String diagnostic = VulkanInstanceExtensionDiagnostics.format(createInfo);
                if (repaired) diagnostic += " workaround=rewrote-truncated-extension-name";
                System.err.println("[Native Accelerator] Vulkan instance extension probe: " + diagnostic);
            } catch (Throwable ignored) {
                // Diagnostics must never change RenderPearl's creation or fallback behavior.
            }
            // The create-info extension pointers may be backed by this stack, so the original call
            // must happen before the stack is closed. The Vulkan result is returned unchanged.
            return VK12.vkCreateInstance(createInfo, allocator, instance);
        } finally {
            try {
                stack.close();
            } catch (Throwable ignored) {
                // A diagnostic stack cleanup failure must not replace the Vulkan result/exception.
            }
        }
    }
}
