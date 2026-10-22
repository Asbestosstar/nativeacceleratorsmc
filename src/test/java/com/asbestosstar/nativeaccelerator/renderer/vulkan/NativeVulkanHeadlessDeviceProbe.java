package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;

/**
 * Headless Vulkan discovery diagnostic. It deliberately does not initialize SDL or claim WSI support.
 */
public final class NativeVulkanHeadlessDeviceProbe {
    private static final String REQUIRED_ARG = "--require";

    private NativeVulkanHeadlessDeviceProbe() {
    }

    public static void main(String[] args) {
        boolean required = hasArgument(args, REQUIRED_ARG);
        String stage = "loader";
        VkInstance instance = null;
        VkDevice logicalDevice = null;
        boolean graphicsQueue = false;
        try {
            evidence("starting loader");
            int apiVersion = probeLoader();
            evidence("loader API " + version(apiVersion));

            stage = "instance";
            evidence("starting instance");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkApplicationInfo application = VkApplicationInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                        .pApplicationName(stack.UTF8("Native Accelerator headless Vulkan probe"))
                        .applicationVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                        .pEngineName(stack.UTF8("Native Accelerator"))
                        .engineVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                        .apiVersion(Math.min(VK10.VK_MAKE_VERSION(1, 0, 0), apiVersion));
                VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                        .pApplicationInfo(application);
                PointerBuffer instanceOut = stack.mallocPointer(1);
                int result = VK10.vkCreateInstance(createInfo, null, instanceOut);
                if (result != VK10.VK_SUCCESS) {
                    throw new ProbeFailure("vkCreateInstance returned " + result, false);
                }
                instance = new VkInstance(instanceOut.get(0), createInfo);

                stage = "physical-device enumeration";
                evidence("starting physical-device enumeration");
                VkPhysicalDevice[] devices = enumerateDevices(instance, stack);
                if (devices.length == 0) {
                    finish("BLOCKED", "Vulkan instance created but no physical devices were enumerated", required, 2);
                    return;
                }
                evidence("physical devices=" + devices.length);

                stage = "queue-family inspection";
                int graphicsDevice = -1;
                int graphicsFamily = -1;
                for (int deviceIndex = 0; deviceIndex < devices.length; deviceIndex++) {
                    VkPhysicalDevice device = devices[deviceIndex];
                    VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                    VK10.vkGetPhysicalDeviceProperties(device, properties);
                    evidence("device[" + deviceIndex + "] name=" + properties.deviceNameString()
                            + " vendor=0x" + Integer.toHexString(properties.vendorID())
                            + " id=0x" + Integer.toHexString(properties.deviceID())
                            + " type=" + properties.deviceType()
                            + " api=" + version(properties.apiVersion()));

                    VkQueueFamilyProperties.Buffer families = queueFamilies(device, stack);
                    for (int family = 0; family < families.remaining(); family++) {
                        VkQueueFamilyProperties queue = families.get(family);
                        evidence("device[" + deviceIndex + "] queue[" + family + "] count="
                                + queue.queueCount() + " flags=0x" + Integer.toHexString(queue.queueFlags()));
                        if (graphicsDevice < 0 && queue.queueCount() > 0
                                && (queue.queueFlags() & VK10.VK_QUEUE_GRAPHICS_BIT) != 0) {
                            graphicsDevice = deviceIndex;
                            graphicsFamily = family;
                        }
                    }
                }
                graphicsQueue = graphicsDevice >= 0;
                if (!graphicsQueue) {
                    finish("BLOCKED", "physical devices were found but none expose a graphics queue", required, 2);
                    return;
                }

                stage = "logical-device validation";
                evidence("starting logical-device validation on device[" + graphicsDevice
                        + "] queue[" + graphicsFamily + "]");
                logicalDevice = createMinimalDevice(devices[graphicsDevice], graphicsFamily, stack);
                PointerBuffer queueOut = stack.mallocPointer(1);
                VK10.vkGetDeviceQueue(logicalDevice, graphicsFamily, 0, queueOut);
                if (queueOut.get(0) == 0L) {
                    throw new ProbeFailure("vkGetDeviceQueue returned a null queue", false);
                }
                VkQueue queue = new VkQueue(queueOut.get(0), logicalDevice);
                if (queue.address() == 0L) {
                    throw new ProbeFailure("graphics queue wrapper is null", false);
                }
                int idleResult = VK10.vkDeviceWaitIdle(logicalDevice);
                if (idleResult != VK10.VK_SUCCESS) {
                    throw new ProbeFailure("vkDeviceWaitIdle returned " + idleResult, false);
                }
                VK10.vkDestroyDevice(logicalDevice, null);
                logicalDevice = null;
                evidence("logical-device validation passed");
            } finally {
                stage = "teardown";
                if (logicalDevice != null) {
                    try {
                        VK10.vkDestroyDevice(logicalDevice, null);
                    } catch (Throwable cleanup) {
                        evidence("logical-device cleanup failed: " + message(cleanup));
                    }
                }
                if (instance != null) {
                    VK10.vkDestroyInstance(instance, null);
                    instance = null;
                }
            }
            finish("PASS", "headless loader/instance/device/graphics-queue evidence only; no SDL/WSI claim", false, 0);
        } catch (ProbeFailure failure) {
            finish(failure.blocked ? "BLOCKED" : "FAIL", "at " + stage + ": " + failure.getMessage(),
                    failure.blocked ? required : true, failure.blocked ? 2 : 1);
        } catch (Throwable failure) {
            finish("FAIL", "at " + stage + ": " + message(failure), true, 1);
        }
    }

    private static int probeLoader() throws ProbeFailure {
        try {
            try {
                VK.getFunctionProvider();
            } catch (IllegalStateException notInitialized) {
                VK.create();
            }
            int version = VK.getInstanceVersionSupported();
            if (version <= 0) throw new ProbeFailure("Vulkan loader reported no supported API", true);
            return version;
        } catch (ProbeFailure failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            throw new ProbeFailure("Vulkan loader unavailable: " + message(failure), true);
        }
    }

    private static VkPhysicalDevice[] enumerateDevices(VkInstance instance, MemoryStack stack)
            throws ProbeFailure {
        for (int attempt = 0; attempt < 3; attempt++) {
            IntBuffer count = stack.ints(0);
            int result = VK10.vkEnumeratePhysicalDevices(instance, count, null);
            if (result != VK10.VK_SUCCESS) {
                throw new ProbeFailure("vkEnumeratePhysicalDevices(count) returned " + result, false);
            }
            int capacity = count.get(0);
            if (capacity == 0) return new VkPhysicalDevice[0];
            PointerBuffer handles = stack.mallocPointer(capacity);
            count.put(0, capacity);
            result = VK10.vkEnumeratePhysicalDevices(instance, count, handles);
            int returned = Math.min(count.get(0), capacity);
            if (result == VK10.VK_SUCCESS && returned > 0) {
                VkPhysicalDevice[] devices = new VkPhysicalDevice[returned];
                for (int index = 0; index < returned; index++) {
                    devices[index] = new VkPhysicalDevice(handles.get(index), instance);
                }
                return devices;
            }
            if (result != VK10.VK_INCOMPLETE) {
                throw new ProbeFailure("vkEnumeratePhysicalDevices returned " + result, false);
            }
        }
        throw new ProbeFailure("physical-device enumeration remained incomplete after retries", false);
    }

    private static VkQueueFamilyProperties.Buffer queueFamilies(VkPhysicalDevice device, MemoryStack stack)
            throws ProbeFailure {
        IntBuffer count = stack.ints(0);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        if (count.get(0) <= 0) throw new ProbeFailure("device reported no queue families", false);
        VkQueueFamilyProperties.Buffer result = VkQueueFamilyProperties.calloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, result);
        return result;
    }

    private static VkDevice createMinimalDevice(VkPhysicalDevice physicalDevice, int queueFamily, MemoryStack stack)
            throws ProbeFailure {
        VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(queueFamily)
                .pQueuePriorities(stack.floats(1.0f));
        VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
        VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueInfo)
                .pEnabledFeatures(features);
        PointerBuffer deviceOut = stack.mallocPointer(1);
        int result = VK10.vkCreateDevice(physicalDevice, createInfo, null, deviceOut);
        if (result != VK10.VK_SUCCESS) throw new ProbeFailure("vkCreateDevice returned " + result, false);
        try {
            return new VkDevice(deviceOut.get(0), physicalDevice, createInfo);
        } catch (RuntimeException | LinkageError failure) {
            try {
                VK10.nvkDestroyDevice(new VkDevice(deviceOut.get(0), physicalDevice, createInfo), 0L);
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw new ProbeFailure("could not wrap logical device: " + message(failure), false);
        }
    }

    private static boolean hasArgument(String[] args, String expected) {
        if (args == null) return false;
        for (String arg : args) if (expected.equals(arg)) return true;
        return false;
    }

    private static String version(int value) {
        return (value >>> 22) + "." + ((value >>> 12) & 0x3ff) + "." + (value & 0xfff);
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static void evidence(String message) {
        System.out.println("VULKAN-HEADLESS: " + message);
        System.out.flush();
    }

    private static void finish(String status, String detail, boolean required, int failureCode) {
        System.out.println("NATIVEACCELERATOR_VULKAN_HEADLESS_EVIDENCE: " + status + " (" + detail + ")");
        System.out.flush();
        if (failureCode != 0 && required) System.exit(failureCode);
    }

    private static final class ProbeFailure extends Exception {
        private final boolean blocked;

        private ProbeFailure(String message, boolean blocked) {
            super(message);
            this.blocked = blocked;
        }
    }
}
