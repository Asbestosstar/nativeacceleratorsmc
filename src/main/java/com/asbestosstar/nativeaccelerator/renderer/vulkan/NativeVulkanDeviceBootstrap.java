package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
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

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Public-LWJGL Vulkan instance/device bootstrap for the future RenderPearl device.
 *
 * <p>This class is intentionally not a {@code GpuDevice}. It owns only the Vulkan bootstrap objects
 * and is useful for the next adapter phase: proving that a real loader, instance, physical device,
 * graphics queue, and requested device extensions can be created without using Minecraft's private
 * Vulkan implementation. It does not claim presentation support and therefore must not be used as the
 * complete-adapter capability probe.</p>
 */
public final class NativeVulkanDeviceBootstrap implements AutoCloseable {
    public static final String SWAPCHAIN_EXTENSION = "VK_KHR_swapchain";

    private final VkInstance instance;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final int graphicsQueueFamily;
    private final NativeVulkanQueueFamilies queueFamilies;
    private final VkPhysicalDeviceProperties properties;
    private final Set<String> enabledInstanceExtensions;
    private final Set<String> enabledDeviceExtensions;
    private final Set<Integer> createdQueueFamilies;
    private final boolean ownsHandles;
    private volatile boolean closed;

    private static final Object LOADER_LOCK = new Object();
    private static boolean loaderReady;

    /** Initializes only LWJGL's Vulkan loader dispatch; does not create a Vulkan instance. */
    public static int probeLoaderApiVersion() throws BackendCreationException {
        synchronized (LOADER_LOCK) {
            try {
                if (!loaderReady) {
                    try {
                        VK.getFunctionProvider();
                    } catch (IllegalStateException notInitialized) {
                        VK.create();
                    }
                    loaderReady = true;
                }
                return VK.getInstanceVersionSupported();
            } catch (RuntimeException | LinkageError failure) {
                throw failure("Vulkan loader probe failed: " + safeMessage(failure),
                        BackendCreationException.Reason.VULKAN_LOADER_MISSING);
            }
        }
    }

    private NativeVulkanDeviceBootstrap(
            VkInstance instance,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue graphicsQueue,
            int graphicsQueueFamily,
            NativeVulkanQueueFamilies queueFamilies,
            VkPhysicalDeviceProperties properties,
            Set<String> enabledInstanceExtensions,
            Set<String> enabledDeviceExtensions,
            Set<Integer> createdQueueFamilies) {
        this(instance, physicalDevice, device, graphicsQueue, graphicsQueueFamily, queueFamilies,
                properties, enabledInstanceExtensions, enabledDeviceExtensions, createdQueueFamilies, true);
    }

    private NativeVulkanDeviceBootstrap(
            VkInstance instance,
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue graphicsQueue,
            int graphicsQueueFamily,
            NativeVulkanQueueFamilies queueFamilies,
            VkPhysicalDeviceProperties properties,
            Set<String> enabledInstanceExtensions,
            Set<String> enabledDeviceExtensions,
            Set<Integer> createdQueueFamilies,
            boolean ownsHandles) {
        this.instance = instance;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.graphicsQueue = graphicsQueue;
        this.graphicsQueueFamily = graphicsQueueFamily;
        this.queueFamilies = java.util.Objects.requireNonNull(queueFamilies, "queueFamilies");
        this.properties = properties;
        this.enabledInstanceExtensions = Set.copyOf(enabledInstanceExtensions);
        this.enabledDeviceExtensions = Set.copyOf(enabledDeviceExtensions);
        this.createdQueueFamilies = Set.copyOf(createdQueueFamilies);
        this.ownsHandles = ownsHandles;
    }

    private static VkPhysicalDeviceProperties copyProperties(VkPhysicalDeviceProperties source) {
        VkPhysicalDeviceProperties copy = VkPhysicalDeviceProperties.malloc();
        org.lwjgl.system.MemoryUtil.memCopy(source.address(), copy.address(), VkPhysicalDeviceProperties.SIZEOF);
        return copy;
    }

    static NativeVulkanDeviceBootstrap adopt(VkInstance instance,
                                               VkPhysicalDevice physicalDevice,
                                               VkDevice device,
                                               VkQueue graphicsQueue,
                                               int graphicsQueueFamily,
                                               NativeVulkanQueueFamilies queueFamilies,
                                               VkPhysicalDeviceProperties properties,
                                               Set<String> enabledInstanceExtensions,
                                               Set<String> enabledDeviceExtensions,
                                               Set<Integer> createdQueueFamilies) {
        return new NativeVulkanDeviceBootstrap(instance, physicalDevice, device, graphicsQueue,
                graphicsQueueFamily, queueFamilies, properties, enabledInstanceExtensions,
                enabledDeviceExtensions, createdQueueFamilies, false);
    }

    /**
     * Creates a device with one graphics queue and the explicitly requested device extensions.
     * Presentation support is deliberately not inferred here; the caller must supply and validate a
     * real surface before enabling the complete RenderPearl candidate.
     */
    public static NativeVulkanDeviceBootstrap create(GpuDebugOptions debugOptions,
                                                      Set<String> requestedDeviceExtensions)
            throws BackendCreationException {
        return createInternal(debugOptions, requestedDeviceExtensions, Set.of());
    }

    /**
     * Creates a bootstrap that enables the instance extensions required by a validated native window
     * descriptor and the device swapchain extension. Presentation support is still checked only after
     * a real {@code VkSurfaceKHR} exists; this method does not fabricate a surface or a present queue.
     */
    public static NativeVulkanDeviceBootstrap createWithWsi(GpuDebugOptions debugOptions,
                                                              NativeVulkanWsiDescriptor wsi)
            throws BackendCreationException {
        if (wsi == null) throw new IllegalArgumentException("wsi must not be null");
        return createInternal(debugOptions, Set.of(SWAPCHAIN_EXTENSION),
                wsi.requiredInstanceExtensions());
    }
    /** Creates a bootstrap with instance extensions obtained from SDL before the real window is attached. */
    public static NativeVulkanDeviceBootstrap createWithWsiExtensions(GpuDebugOptions debugOptions,
                                                                       Set<String> requiredInstanceExtensions)
            throws BackendCreationException {
        if (requiredInstanceExtensions == null || requiredInstanceExtensions.isEmpty()) {
            throw new IllegalArgumentException("requiredInstanceExtensions must not be empty");
        }
        return createInternal(debugOptions, Set.of(SWAPCHAIN_EXTENSION), requiredInstanceExtensions);
    }
    private static NativeVulkanDeviceBootstrap createInternal(GpuDebugOptions debugOptions,
                                                                Set<String> requestedDeviceExtensions,
                                                                Set<String> requestedInstanceExtensions)
            throws BackendCreationException {
        Set<String> requested = validateExtensions(requestedDeviceExtensions);
        Set<String> requiredInstance = validateExtensions(requestedInstanceExtensions);
        VkInstance instance = null;
        VkDevice device = null;
        VkPhysicalDeviceProperties allocatedProperties = null;
        Set<String> enabledInstanceExtensions = Set.of();
        long rawInstance = 0L;
        long rawDevice = 0L;
        try {
            ensureVulkanLoader();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                Set<String> instanceExtensions = enumerateInstanceExtensions(stack);
                List<String> missingInstance = requiredInstance.stream()
                        .filter(extension -> !instanceExtensions.contains(extension))
                        .sorted()
                        .toList();
                if (!missingInstance.isEmpty()) {
                    throw new BackendCreationException(
                            "Vulkan instance is missing required extensions: "
                                    + String.join(", ", missingInstance),
                            BackendCreationException.Reason.VULKAN_MISSING_EXTENSION,
                            missingInstance);
                }
                enabledInstanceExtensions = new HashSet<>(requiredInstance);
                Set<String> enabledLayers = new HashSet<>();
                // These are not sufficient for WSI; platform-specific surface extensions are added by
                // the later WSI bootstrap after actual display/window evidence is available.
                if (debugOptions != null && debugOptions.useValidationLayers()) {
                    Set<String> layers = enumerateInstanceLayers(stack);
                    if (!layers.contains("VK_LAYER_KHRONOS_validation")) {
                        throw failure("Vulkan validation was requested but VK_LAYER_KHRONOS_validation is unavailable",
                                BackendCreationException.Reason.VULKAN_MISSING_FEATURE);
                    }
                    enabledLayers.add("VK_LAYER_KHRONOS_validation");
                    if (instanceExtensions.contains("VK_EXT_debug_utils")) {
                        enabledInstanceExtensions.add("VK_EXT_debug_utils");
                    }
                }

                VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                        .pApplicationName(stack.UTF8("Native Accelerator RenderPearl adapter"))
                        .applicationVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                        .pEngineName(stack.UTF8("Native Accelerator"))
                        .engineVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                        .apiVersion(Math.min(VK10.VK_MAKE_VERSION(1, 0, 0), supportedApiVersion()));
                PointerBuffer extensionNames = utf8Pointers(stack, enabledInstanceExtensions);
                PointerBuffer layerNames = utf8Pointers(stack, enabledLayers);
                VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                        .pApplicationInfo(app)
                        .ppEnabledExtensionNames(extensionNames)
                        .ppEnabledLayerNames(layerNames);
                PointerBuffer instanceOut = stack.mallocPointer(1);
                int result = VK10.vkCreateInstance(createInfo, null, instanceOut);
                if (result != VK10.VK_SUCCESS) {
                    throw failure("vkCreateInstance failed with " + result,
                            BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED);
                }
                rawInstance = instanceOut.get(0);
                try {
                    instance = new VkInstance(rawInstance, createInfo);
                } catch (RuntimeException | LinkageError failure) {
                    destroyRawInstance(rawInstance, createInfo);
                    rawInstance = 0L;
                    throw failure;
                }
                rawInstance = 0L;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDevice physicalDevice = selectPhysicalDevice(instance, stack);
                allocatedProperties = VkPhysicalDeviceProperties.calloc();
                VK10.vkGetPhysicalDeviceProperties(physicalDevice, allocatedProperties);
                NativeVulkanQueueFamilies queueFamilies = inspectQueueFamilies(physicalDevice, stack);
                int graphicsFamily = findGraphicsQueueFamily(queueFamilies);
                if (graphicsFamily < 0) {
                    throw failure("No Vulkan graphics queue family was found",
                            BackendCreationException.Reason.VULKAN_NO_GRAPHICS_QUEUE);
                }

                Set<String> availableDeviceExtensions = enumerateDeviceExtensions(physicalDevice, stack);
                List<String> missing = requested.stream()
                        .filter(extension -> !availableDeviceExtensions.contains(extension))
                        .sorted()
                        .toList();
                if (!missing.isEmpty()) {
                    throw new BackendCreationException(
                            "Vulkan device is missing required extensions: " + String.join(", ", missing),
                            BackendCreationException.Reason.VULKAN_MISSING_EXTENSION,
                            missing);
                }

                FloatBufferHolder priority = new FloatBufferHolder(stack);
                VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                        .queueFamilyIndex(graphicsFamily)
                        .pQueuePriorities(priority.buffer);
                PointerBuffer extensionNames = utf8Pointers(stack, requested);
                VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
                VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                        .pQueueCreateInfos(queueInfo)
                        .ppEnabledExtensionNames(extensionNames)
                        .pEnabledFeatures(features);
                PointerBuffer deviceOut = stack.mallocPointer(1);
                int result = VK10.vkCreateDevice(physicalDevice, deviceInfo, null, deviceOut);
                if (result != VK10.VK_SUCCESS) {
                    throw failure("vkCreateDevice failed with " + result,
                            result == VK10.VK_ERROR_FEATURE_NOT_PRESENT
                                    ? BackendCreationException.Reason.VULKAN_MISSING_FEATURE
                                    : result == VK10.VK_ERROR_EXTENSION_NOT_PRESENT
                                    ? BackendCreationException.Reason.VULKAN_MISSING_EXTENSION
                                    : BackendCreationException.Reason.PLATFORM_ERROR);
                }
                rawDevice = deviceOut.get(0);
                try {
                    device = new VkDevice(rawDevice, physicalDevice, deviceInfo);
                } catch (RuntimeException | LinkageError failure) {
                    destroyRawDevice(rawDevice, physicalDevice, deviceInfo);
                    rawDevice = 0L;
                    throw failure;
                }
                rawDevice = 0L;
                PointerBuffer queueOut = stack.mallocPointer(1);
                VK10.vkGetDeviceQueue(device, graphicsFamily, 0, queueOut);
                VkQueue queue = new VkQueue(queueOut.get(0), device);
                NativeVulkanDeviceBootstrap bootstrap = new NativeVulkanDeviceBootstrap(instance, physicalDevice, device, queue,
                        graphicsFamily, queueFamilies, allocatedProperties, enabledInstanceExtensions, requested,
                        Set.of(graphicsFamily));
                allocatedProperties = null;
                return bootstrap;
            }
        } catch (BackendCreationException failure) {
            if (allocatedProperties != null) allocatedProperties.free();
            destroyPartial(device, instance, rawDevice, rawInstance);
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            if (allocatedProperties != null) allocatedProperties.free();
            destroyPartial(device, instance, rawDevice, rawInstance);
            throw failure("Vulkan bootstrap failed: " + safeMessage(failure),
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }

    public VkInstance instance() {
        requireOpen();
        return instance;
    }

    public VkPhysicalDevice physicalDevice() {
        requireOpen();
        return physicalDevice;
    }

    public VkDevice device() {
        requireOpen();
        return device;
    }

    public VkQueue graphicsQueue() {
        requireOpen();
        return graphicsQueue;
    }

    public int graphicsQueueFamily() {
        requireOpen();
        return graphicsQueueFamily;
    }

    public NativeVulkanQueueFamilies queueFamilies() {
        requireOpen();
        return queueFamilies;
    }

    /**
     * Returns a detached copy of the physical-device properties. The returned struct is owned by the
     * caller and must be freed; the bootstrap keeps its own allocation private.
     */
    public VkPhysicalDeviceProperties properties() {
        requireOpen();
        VkPhysicalDeviceProperties copy = VkPhysicalDeviceProperties.malloc();
        MemoryUtil.memCopy(properties.address(), copy.address(), VkPhysicalDeviceProperties.SIZEOF);
        return copy;
    }

    public Set<String> enabledInstanceExtensions() {
        return enabledInstanceExtensions;
    }

    public Set<String> enabledDeviceExtensions() {
        return enabledDeviceExtensions;
    }

    public boolean queueCreated(int queueFamily) {
        return createdQueueFamilies.contains(queueFamily);
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with Vulkan result " + result);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!ownsHandles) return;
        Throwable first = null;
        try {
            check(VK10.vkDeviceWaitIdle(device), "wait for device before bootstrap teardown");
        } catch (Throwable failure) {
            first = failure;
        } finally {
            try {
                VK10.vkDestroyDevice(device, null);
            } catch (Throwable failure) {
                if (first == null) first = failure;
                else first.addSuppressed(failure);
            } finally {
                try {
                    properties.free();
                } catch (Throwable failure) {
                    if (first == null) first = failure;
                    else first.addSuppressed(failure);
                } finally {
                    try {
                        VK10.vkDestroyInstance(instance, null);
                    } catch (Throwable failure) {
                        if (first == null) first = failure;
                        else first.addSuppressed(failure);
                    }
                }
            }
        }
        if (first != null) {
            if (first instanceof RuntimeException runtime) throw runtime;
            if (first instanceof Error error) throw error;
            throw new IllegalStateException("Vulkan bootstrap cleanup failed", first);
        }
    }


    private void requireOpen() {
        if (closed) throw new IllegalStateException("Vulkan bootstrap is closed");
    }

    private static void ensureVulkanLoader() throws BackendCreationException {
        int apiVersion = probeLoaderApiVersion();
        if (apiVersion <= 0) {
            throw failure("Vulkan loader reported no supported instance API version",
                    BackendCreationException.Reason.VULKAN_LOADER_MISSING);
        }
    }

    private static int supportedApiVersion() {
        try {
            return VK.getInstanceVersionSupported();
        } catch (RuntimeException | LinkageError ignored) {
            return VK10.VK_API_VERSION_1_0;
        }
    }

    private static Set<String> enumerateInstanceLayers(MemoryStack stack)
            throws BackendCreationException {
        IntBuffer count = stack.ints(0);
        int result = VK10.vkEnumerateInstanceLayerProperties(count, null);
        if (result != VK10.VK_SUCCESS) {
            throw failure("vkEnumerateInstanceLayerProperties failed with " + result,
                    BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED);
        }
        var properties = org.lwjgl.vulkan.VkLayerProperties.calloc(count.get(0), stack);
        result = VK10.vkEnumerateInstanceLayerProperties(count, properties);
        if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) {
            throw failure("vkEnumerateInstanceLayerProperties failed with " + result,
                    BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED);
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < Math.min(count.get(0), properties.capacity()); i++) {
            names.add(properties.get(i).layerNameString());
        }
        return names;
    }

    private static Set<String> enumerateInstanceExtensions(MemoryStack stack)
            throws BackendCreationException {
        IntBuffer count = stack.ints(0);
        int result = VK10.vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null);
        if (result != VK10.VK_SUCCESS) {
            throw failure("vkEnumerateInstanceExtensionProperties failed with " + result,
                    BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED);
        }
        var properties = org.lwjgl.vulkan.VkExtensionProperties.calloc(count.get(0), stack);
        int capacity = properties.capacity();
        result = VK10.vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, properties);
        if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) {
            throw failure("vkEnumerateInstanceExtensionProperties failed with " + result,
                    BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED);
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < Math.min(count.get(0), capacity); i++) names.add(properties.get(i).extensionNameString());
        return names;
    }

    private static Set<String> enumerateDeviceExtensions(VkPhysicalDevice physicalDevice, MemoryStack stack)
            throws BackendCreationException {
        IntBuffer count = stack.ints(0);
        int result = VK10.vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, null);
        if (result != VK10.VK_SUCCESS) {
            throw failure("vkEnumerateDeviceExtensionProperties failed with " + result,
                    BackendCreationException.Reason.VULKAN_NO_DEVICE);
        }
        var properties = org.lwjgl.vulkan.VkExtensionProperties.calloc(count.get(0), stack);
        int capacity = properties.capacity();
        result = VK10.vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, properties);
        if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) {
            throw failure("vkEnumerateDeviceExtensionProperties failed with " + result,
                    BackendCreationException.Reason.VULKAN_NO_DEVICE);
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < Math.min(count.get(0), capacity); i++) names.add(properties.get(i).extensionNameString());
        return names;
    }

    private static VkPhysicalDevice selectPhysicalDevice(VkInstance instance, MemoryStack stack)
            throws BackendCreationException {
        IntBuffer count = stack.ints(0);
        int result = VK10.vkEnumeratePhysicalDevices(instance, count, null);
        if (result != VK10.VK_SUCCESS || count.get(0) == 0) {
            throw failure("No Vulkan physical device was found",
                    BackendCreationException.Reason.VULKAN_NO_DEVICE);
        }
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        result = VK10.vkEnumeratePhysicalDevices(instance, count, devices);
        if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) {
            throw failure("vkEnumeratePhysicalDevices failed with " + result,
                    BackendCreationException.Reason.VULKAN_NO_DEVICE);
        }
        // Device scoring belongs to the later RenderPearl capability layer. This bootstrap only chooses
        // the first device that actually exposes a graphics queue.
        for (int i = 0; i < count.get(0); i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
            if (findGraphicsQueueFamily(candidate, stack) >= 0) return candidate;
        }
        throw failure("Enumerated Vulkan devices have no graphics queue",
                BackendCreationException.Reason.VULKAN_NO_GRAPHICS_QUEUE);
    }

    private static NativeVulkanQueueFamilies inspectQueueFamilies(VkPhysicalDevice physicalDevice,
                                                                    MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
        if (count.get(0) == 0) {
            throw new IllegalArgumentException("Vulkan physical device reported no queue families");
        }
        VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.calloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, properties);
        int[] flags = new int[count.get(0)];
        int[] queues = new int[count.get(0)];
        for (int i = 0; i < count.get(0); i++) {
            flags[i] = properties.get(i).queueFlags();
            queues[i] = properties.get(i).queueCount();
        }
        return new NativeVulkanQueueFamilies(flags, queues);
    }

    private static int findGraphicsQueueFamily(NativeVulkanQueueFamilies queueFamilies) {
        int[] flags = queueFamilies.queueFlags();
        int[] counts = queueFamilies.queueCounts();
        for (int i = 0; i < flags.length; i++) {
            if (counts[i] > 0 && (flags[i] & VK10.VK_QUEUE_GRAPHICS_BIT) != 0) return i;
        }
        return -1;
    }

    private static int findGraphicsQueueFamily(VkPhysicalDevice physicalDevice, MemoryStack stack) {
        return findGraphicsQueueFamily(inspectQueueFamilies(physicalDevice, stack));
    }

    private static PointerBuffer utf8Pointers(MemoryStack stack, Set<String> values) {
        PointerBuffer result = stack.mallocPointer(values.size());
        for (String value : values) result.put(stack.UTF8(value));
        return result.flip();
    }

    private static Set<String> validateExtensions(Set<String> extensions)
            throws BackendCreationException {
        if (extensions == null || extensions.isEmpty()) return Set.of();
        HashSet<String> validated = new HashSet<>();
        for (String extension : extensions) {
            if (extension == null || extension.isBlank()) {
                throw failure("Vulkan device extension names must be non-blank",
                        BackendCreationException.Reason.VULKAN_MISSING_EXTENSION);
            }
            validated.add(extension);
        }
        return Set.copyOf(validated);
    }

    private static void destroyPartial(VkDevice device, VkInstance instance,
                                       long rawDevice, long rawInstance) {
        try {
            if (device != null) VK10.vkDestroyDevice(device, null);
        } catch (Throwable ignored) {
        }
        // Raw-handle branches are normally consumed by the constructor guards, which retain the
        // VkPhysicalDevice/VkDeviceCreateInfo metadata required to build a dispatch wrapper.
        // If a later failure leaves a raw handle behind, there is no safe public LWJGL destroy entry
        // point without those wrappers; keep this path diagnostic rather than constructing invalid
        // wrappers with null metadata.
        try {
            if (instance != null) VK10.vkDestroyInstance(instance, null);
        } catch (Throwable ignored) {
        }
    }

    private static void destroyRawInstance(long handle, VkInstanceCreateInfo createInfo) {
        // VkInstance's constructor is the public LWJGL path that resolves instance dispatch. If it
        // fails, retrying wrapper construction is unsafe; the raw handle is retained only for this
        // final best-effort cleanup attempt with the original create-info metadata.
        try {
            VK10.nvkDestroyInstance(new VkInstance(handle, createInfo), 0L);
        } catch (Throwable ignored) {
        }
    }

    private static void destroyRawDevice(long handle, VkPhysicalDevice physicalDevice,
                                         VkDeviceCreateInfo createInfo) {
        try {
            VK10.nvkDestroyDevice(new VkDevice(handle, physicalDevice, createInfo), 0L);
        } catch (Throwable ignored) {
        }
    }

    private static BackendCreationException failure(String message, BackendCreationException.Reason reason) {
        return new BackendCreationException(message, reason);
    }

    private static String safeMessage(Throwable failure) {
        StringBuilder text = new StringBuilder(describe(failure));
        Throwable cause = failure.getCause();
        for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
            text.append(" <- ").append(describe(cause));
        }
        return text.toString();
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        String name = failure.getClass().getName();
        return message == null || message.isBlank() ? name : name + ": " + message;
    }

    private static final class FloatBufferHolder {
        private final java.nio.FloatBuffer buffer;
        private FloatBufferHolder(MemoryStack stack) {
            this.buffer = stack.floats(1.0f);
        }
    }
}
