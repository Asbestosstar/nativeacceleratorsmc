package com.asbestosstar.nativeaccelerator.renderer.vulkan;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns an SDL surface and a Vulkan device whose graphics and presentation queues were selected
 * against that exact surface. This bootstrap is not a complete RenderPearl adapter readiness claim.
 */
public final class NativeVulkanOwnedWsiBootstrap implements AutoCloseable {
    private final VkInstance instance;
    private final long surface;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final VkQueue presentQueue;
    private final NativeVulkanQueueSelection.Selection queues;
    private final VkPhysicalDeviceProperties properties;
    private final Set<String> enabledInstanceExtensions;
    private final Set<String> enabledDeviceExtensions;
    private final NativeVulkanDeviceCore core;
    private final long windowHandle;
    private boolean closed;

    private NativeVulkanOwnedWsiBootstrap(VkInstance instance, long surface,
            VkPhysicalDevice physicalDevice, VkDevice device, VkQueue graphicsQueue,
            VkQueue presentQueue, NativeVulkanQueueSelection.Selection queues,
            VkPhysicalDeviceProperties properties, Set<String> enabledInstanceExtensions,
            Set<String> enabledDeviceExtensions, NativeVulkanDeviceCore core, long windowHandle) {
        this.instance = instance;
        this.surface = surface;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.graphicsQueue = graphicsQueue;
        this.presentQueue = presentQueue;
        this.queues = queues;
        this.properties = properties;
        this.enabledInstanceExtensions = Set.copyOf(enabledInstanceExtensions);
        this.enabledDeviceExtensions = Set.copyOf(enabledDeviceExtensions);
        this.core = core;
        this.windowHandle = windowHandle;
    }

    long windowHandle() { return windowHandle; }

    static NativeVulkanOwnedWsiBootstrap attach(GpuDebugOptions debugOptions, long sdlWindow,
                                                 NativeVulkanDeviceCore core)
            throws BackendCreationException {
        if (sdlWindow == 0L) throw new IllegalArgumentException("sdlWindow must be non-zero");
        if (core == null) throw new IllegalArgumentException("core must not be null");
        NativeVulkanDeviceBootstrap device = core.bootstrap();
        VkInstance instance = device.instance();
        long surface = 0L;
        VkPhysicalDeviceProperties properties = null;
        try {
            Set<String> requiredExtensions = SdlVulkanWsiBridge.requiredInstanceExtensions();
            requireExtensions(device.enabledInstanceExtensions(), requiredExtensions, "device instance");
            surface = SdlVulkanWsiBridge.createSurface(sdlWindow, instance);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                NativeVulkanQueueFamilies families = device.queueFamilies();
                int[] flags = families.queueFlags();
                int[] counts = families.queueCounts();
                boolean[] supportsPresent = new boolean[flags.length];
                for (int family = 0; family < counts.length; family++) {
                    if (counts[family] == 0) continue;
                    IntBuffer supported = stack.ints(0);
                    int result = KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(
                            device.physicalDevice(), family, surface, supported);
                    if (result != VK10.VK_SUCCESS) throw failure(
                            "Could not query Vulkan presentation support for queue family " + family + ": " + result,
                            BackendCreationException.Reason.VULKAN_NO_GRAPHICS_QUEUE);
                    supportsPresent[family] = supported.get(0) != 0;
                }
                NativeVulkanQueueSelection.Selection selected;
                try { selected = NativeVulkanQueueSelection.select(flags, counts, supportsPresent); }
                catch (IllegalArgumentException unsupported) {
                    throw failure("The existing Vulkan graphics queue cannot present to Minecraft's SDL surface",
                            BackendCreationException.Reason.VULKAN_NO_GRAPHICS_QUEUE);
                }
                if (selected.graphicsFamily() != device.graphicsQueueFamily()) throw failure(
                        "The SDL surface requires a graphics queue different from the already-created device queue",
                        BackendCreationException.Reason.VULKAN_NO_GRAPHICS_QUEUE);
                if (!device.queueCreated(selected.presentFamily())) throw failure(
                        "The selected Vulkan present queue family was not created on the existing device",
                        BackendCreationException.Reason.VULKAN_NO_GRAPHICS_QUEUE);
                properties = device.properties();
                Set<String> enabledDeviceExtensions = device.enabledDeviceExtensions();
                if (!enabledDeviceExtensions.contains(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
                    throw failure("Vulkan device was created without VK_KHR_swapchain",
                            BackendCreationException.Reason.VULKAN_MISSING_EXTENSION);
                }
                NativeVulkanOwnedWsiBootstrap attached = new NativeVulkanOwnedWsiBootstrap(
                        instance, surface, device.physicalDevice(), device.device(), device.graphicsQueue(),
                        getQueue(device.device(), selected.presentFamily(), stack), selected, properties,
                        device.enabledInstanceExtensions(), enabledDeviceExtensions, core, sdlWindow);
                surface = 0L;
                properties = null;
                return attached;
            }
        } catch (BackendCreationException failure) {
            if (properties != null) properties.free();
            if (surface != 0L) SdlVulkanWsiBridge.destroySurface(instance, surface);
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            if (properties != null) properties.free();
            if (surface != 0L) SdlVulkanWsiBridge.destroySurface(instance, surface);
            throw failure("Could not attach Vulkan WSI to the existing device: " + safeMessage(failure),
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }

    /** Creates an owned instance, SDL surface, suitable physical device, logical device, and queues. */
    public static NativeVulkanOwnedWsiBootstrap create(GpuDebugOptions debugOptions, long sdlWindow)
            throws BackendCreationException {
        return create(debugOptions, sdlWindow, null);
    }

    static NativeVulkanOwnedWsiBootstrap create(GpuDebugOptions debugOptions, long sdlWindow,
                                                 NativeVulkanDeviceCore core)
            throws BackendCreationException {
        if (sdlWindow == 0L) throw new IllegalArgumentException("sdlWindow must be non-zero");
        if (core != null) {
            throw new UnsupportedOperationException(
                    "A real SDL surface must be created from the device-owned instance before WSI queue validation");
        }
        VkInstance instance = null;
        VkDevice device = null;
        long surface = 0L;
        VkPhysicalDeviceProperties properties = null;
        try {
            ensureLoader();
            Set<String> instanceExtensions = SdlVulkanWsiBridge.requiredInstanceExtensions();
            if (!instanceExtensions.contains(KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME)) {
                throw failure("SDL did not require VK_KHR_surface", BackendCreationException.Reason.VULKAN_MISSING_EXTENSION);
            }
            Set<String> availableInstanceExtensions;
            Set<String> enabledLayers = new HashSet<>();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                availableInstanceExtensions = enumerateInstanceExtensions(stack);
                requireExtensions(availableInstanceExtensions, instanceExtensions, "instance");
                if (debugOptions != null && debugOptions.useValidationLayers()) {
                    Set<String> layers = enumerateInstanceLayers(stack);
                    if (!layers.contains("VK_LAYER_KHRONOS_validation")) {
                        throw failure("Requested Vulkan validation layer is unavailable",
                                BackendCreationException.Reason.VULKAN_MISSING_FEATURE);
                    }
                    enabledLayers.add("VK_LAYER_KHRONOS_validation");
                }
                VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                        .pApplicationName(stack.UTF8("Native Accelerator RenderPearl"))
                        .applicationVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                        .pEngineName(stack.UTF8("Native Accelerator"))
                        .engineVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                        .apiVersion(Math.min(VK10.VK_MAKE_VERSION(1, 0, 0), supportedApiVersion()));
                VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                        .pApplicationInfo(app)
                        .ppEnabledExtensionNames(utf8Pointers(stack, instanceExtensions))
                        .ppEnabledLayerNames(utf8Pointers(stack, enabledLayers));
                PointerBuffer output = stack.mallocPointer(1);
                int result = VK10.vkCreateInstance(createInfo, null, output);
                if (result != VK10.VK_SUCCESS) {
                    throw failure("vkCreateInstance failed with " + result,
                            BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED);
                }
                long rawInstance = output.get(0);
                try {
                    instance = new VkInstance(rawInstance, createInfo);
                } catch (Throwable constructionFailure) {
                    try { VK10.nvkDestroyInstance(new VkInstance(rawInstance, createInfo), 0L); }
                    catch (Throwable cleanup) { constructionFailure.addSuppressed(cleanup); }
                    throw constructionFailure;
                }
            }

            surface = SdlVulkanWsiBridge.createSurface(sdlWindow, instance);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                DeviceCandidate selected = enumerateCandidates(instance, surface, stack).stream()
                        .filter(candidate -> candidate.extensions().contains(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME))
                        .findFirst()
                        .orElseThrow(() -> failure("No physical device supports graphics, presentation, and VK_KHR_swapchain",
                                BackendCreationException.Reason.VULKAN_NO_DEVICE));
                properties = VkPhysicalDeviceProperties.calloc();
                VK10.vkGetPhysicalDeviceProperties(selected.device(), properties);
                LinkedHashSet<Integer> uniqueFamilies = new LinkedHashSet<>();
                uniqueFamilies.add(selected.queues().graphicsFamily());
                uniqueFamilies.add(selected.queues().presentFamily());
                VkDeviceQueueCreateInfo.Buffer queueInfos = VkDeviceQueueCreateInfo.calloc(uniqueFamilies.size(), stack);
                int queueInfoIndex = 0;
                for (int family : uniqueFamilies) {
                    queueInfos.get(queueInfoIndex++).sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                            .queueFamilyIndex(family).pQueuePriorities(stack.floats(1.0f));
                }
                Set<String> requestedDeviceExtensions = Set.of(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);
                VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                        .pQueueCreateInfos(queueInfos)
                        .ppEnabledExtensionNames(utf8Pointers(stack, requestedDeviceExtensions))
                        .pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(stack));
                PointerBuffer deviceOut = stack.mallocPointer(1);
                int result = VK10.vkCreateDevice(selected.device(), deviceInfo, null, deviceOut);
                if (result != VK10.VK_SUCCESS) {
                    throw failure("vkCreateDevice failed with " + result,
                            result == VK10.VK_ERROR_FEATURE_NOT_PRESENT
                                    ? BackendCreationException.Reason.VULKAN_MISSING_FEATURE
                                    : result == VK10.VK_ERROR_EXTENSION_NOT_PRESENT
                                    ? BackendCreationException.Reason.VULKAN_MISSING_EXTENSION
                                    : BackendCreationException.Reason.PLATFORM_ERROR);
                }
                long rawDevice = deviceOut.get(0);
                try {
                    device = new VkDevice(rawDevice, selected.device(), deviceInfo);
                } catch (Throwable constructionFailure) {
                    try { VK10.nvkDestroyDevice(new VkDevice(rawDevice, selected.device(), deviceInfo), 0L); }
                    catch (Throwable cleanup) { constructionFailure.addSuppressed(cleanup); }
                    throw constructionFailure;
                }
                VkQueue graphics = getQueue(device, selected.queues().graphicsFamily(), stack);
                VkQueue present = getQueue(device, selected.queues().presentFamily(), stack);
                NativeVulkanOwnedWsiBootstrap owned = new NativeVulkanOwnedWsiBootstrap(
                        instance, surface, selected.device(), device, graphics, present,
                        selected.queues(), properties, instanceExtensions, requestedDeviceExtensions, core, sdlWindow);
                instance = null;
                surface = 0L;
                device = null;
                properties = null;
                return owned;
            }
        } catch (BackendCreationException failure) {
            cleanup(device, instance, surface, properties);
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            cleanup(device, instance, surface, properties);
            throw failure("SDL/Vulkan WSI bootstrap failed: " + safeMessage(failure),
                    BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }

    public VkInstance instance() { requireOpen(); return instance; }
    public long surface() { requireOpen(); return surface; }
    public VkPhysicalDevice physicalDevice() { requireOpen(); return physicalDevice; }
    public VkDevice device() { requireOpen(); return device; }
    public VkQueue graphicsQueue() { requireOpen(); return graphicsQueue; }
    public VkQueue presentQueue() { requireOpen(); return presentQueue; }
    public int graphicsQueueFamily() { requireOpen(); return queues.graphicsFamily(); }
    public int presentQueueFamily() { requireOpen(); return queues.presentFamily(); }
    public Set<String> enabledInstanceExtensions() { return enabledInstanceExtensions; }
    public Set<String> enabledDeviceExtensions() { return enabledDeviceExtensions; }

    NativeVulkanDeviceBootstrap deviceBootstrap() {
        requireOpen();
        NativeVulkanQueueFamilies families;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            families = inspectQueueFamilies(physicalDevice, stack);
        }
        return NativeVulkanDeviceBootstrap.adopt(instance, physicalDevice, device, graphicsQueue,
                queues.graphicsFamily(), families, properties, enabledInstanceExtensions,
                enabledDeviceExtensions, Set.of(queues.graphicsFamily(), queues.presentFamily()));
    }

    public VkPhysicalDeviceProperties properties() {
        requireOpen();
        VkPhysicalDeviceProperties copy = VkPhysicalDeviceProperties.malloc();
        MemoryUtil.memCopy(properties.address(), copy.address(), VkPhysicalDeviceProperties.SIZEOF);
        return copy;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (core != null) {
            try { SdlVulkanWsiBridge.destroySurface(instance, surface); }
            finally { properties.free(); }
            return;
        }
        Throwable first = null;
        try { VK10.vkDeviceWaitIdle(device); } catch (Throwable error) { first = error; }
        try { VK10.vkDestroyDevice(device, null); }
        catch (Throwable error) { first = append(first, error); }
        try { properties.free(); }
        catch (Throwable error) { first = append(first, error); }
        try { SdlVulkanWsiBridge.destroySurface(instance, surface); }
        catch (Throwable error) { first = append(first, error); }
        try { VK10.vkDestroyInstance(instance, null); }
        catch (Throwable error) { first = append(first, error); }
        if (first != null) throw new IllegalStateException("Vulkan WSI bootstrap cleanup failed", first);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Vulkan WSI bootstrap is closed");
    }

    private static List<DeviceCandidate> enumerateCandidates(VkInstance instance, long surface,
                                                               MemoryStack stack)
            throws BackendCreationException {
        IntBuffer count = stack.ints(0);
        int result = VK10.vkEnumeratePhysicalDevices(instance, count, null);
        if (result != VK10.VK_SUCCESS || count.get(0) == 0) {
            throw failure("No Vulkan physical device was found", BackendCreationException.Reason.VULKAN_NO_DEVICE);
        }
        PointerBuffer handles = stack.mallocPointer(count.get(0));
        result = VK10.vkEnumeratePhysicalDevices(instance, count, handles);
        if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) {
            throw failure("vkEnumeratePhysicalDevices failed with " + result,
                    BackendCreationException.Reason.VULKAN_NO_DEVICE);
        }
        List<DeviceCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < count.get(0); index++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(handles.get(index), instance);
            NativeVulkanQueueFamilies families = inspectQueueFamilies(candidate, stack);
            int[] flags = families.queueFlags();
            int[] queueCounts = families.queueCounts();
            boolean[] present = new boolean[flags.length];
            for (int family = 0; family < flags.length; family++) {
                if (queueCounts[family] == 0) continue;
                IntBuffer support = stack.ints(0);
                int supportResult = KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(candidate, family, surface, support);
                if (supportResult == VK10.VK_SUCCESS) present[family] = support.get(0) != 0;
            }
            NativeVulkanQueueSelection.Selection selection;
            try { selection = NativeVulkanQueueSelection.select(flags, queueCounts, present); }
            catch (IllegalArgumentException unsuitable) { continue; }
            candidates.add(new DeviceCandidate(candidate, selection, enumerateDeviceExtensions(candidate, stack)));
        }
        if (candidates.isEmpty()) throw failure(
                "No device has a queue family supporting graphics, compute, and this SDL surface",
                BackendCreationException.Reason.VULKAN_NO_GRAPHICS_QUEUE);
        return candidates;
    }

    private static NativeVulkanQueueFamilies inspectQueueFamilies(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, null);
        if (count.get(0) <= 0) throw new IllegalArgumentException("Vulkan device has no queue families");
        VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.calloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, properties);
        int[] flags = new int[count.get(0)];
        int[] counts = new int[count.get(0)];
        for (int i = 0; i < count.get(0); i++) {
            flags[i] = properties.get(i).queueFlags();
            counts[i] = properties.get(i).queueCount();
        }
        return new NativeVulkanQueueFamilies(flags, counts);
    }

    private static VkQueue getQueue(VkDevice device, int family, MemoryStack stack) {
        PointerBuffer queue = stack.mallocPointer(1);
        VK10.vkGetDeviceQueue(device, family, 0, queue);
        if (queue.get(0) == 0L) throw new IllegalStateException("vkGetDeviceQueue returned null for family " + family);
        return new VkQueue(queue.get(0), device);
    }

    private static Set<String> enumerateInstanceExtensions(MemoryStack stack)
            throws BackendCreationException {
        IntBuffer count = stack.ints(0);
        int result = VK10.vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null);
        if (result != VK10.VK_SUCCESS) throw failure("Instance extension enumeration failed: " + result,
                BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED);
        VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0), stack);
        result = VK10.vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, properties);
        if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) throw failure(
                "Instance extension enumeration failed: " + result,
                BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED);
        Set<String> resultNames = new HashSet<>();
        for (int i = 0; i < properties.capacity(); i++) resultNames.add(properties.get(i).extensionNameString());
        return resultNames;
    }

    private static Set<String> enumerateInstanceLayers(MemoryStack stack)
            throws BackendCreationException {
        IntBuffer count = stack.ints(0);
        int result = VK10.vkEnumerateInstanceLayerProperties(count, null);
        if (result != VK10.VK_SUCCESS) throw failure("Instance layer enumeration failed: " + result,
                BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED);
        VkLayerProperties.Buffer properties = VkLayerProperties.calloc(count.get(0), stack);
        result = VK10.vkEnumerateInstanceLayerProperties(count, properties);
        if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) throw failure(
                "Instance layer enumeration failed: " + result,
                BackendCreationException.Reason.VULKAN_INSTANCE_CREATION_FAILED);
        Set<String> resultNames = new HashSet<>();
        for (int i = 0; i < properties.capacity(); i++) resultNames.add(properties.get(i).layerNameString());
        return resultNames;
    }

    private static Set<String> enumerateDeviceExtensions(VkPhysicalDevice physicalDevice, MemoryStack stack)
            throws BackendCreationException {
        IntBuffer count = stack.ints(0);
        int result = VK10.vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, null);
        if (result != VK10.VK_SUCCESS) throw failure("Device extension enumeration failed: " + result,
                BackendCreationException.Reason.VULKAN_NO_DEVICE);
        VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0), stack);
        result = VK10.vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, properties);
        if (result != VK10.VK_SUCCESS && result != VK10.VK_INCOMPLETE) throw failure(
                "Device extension enumeration failed: " + result, BackendCreationException.Reason.VULKAN_NO_DEVICE);
        Set<String> resultNames = new HashSet<>();
        for (int i = 0; i < properties.capacity(); i++) resultNames.add(properties.get(i).extensionNameString());
        return resultNames;
    }

    private static PointerBuffer utf8Pointers(MemoryStack stack, Set<String> values) {
        PointerBuffer pointers = stack.mallocPointer(values.size());
        values.forEach(value -> pointers.put(stack.UTF8(value)));
        return pointers.flip();
    }

    private static void requireExtensions(Set<String> available, Set<String> requested, String scope)
            throws BackendCreationException {
        List<String> missing = requested.stream().filter(name -> !available.contains(name)).sorted().toList();
        if (!missing.isEmpty()) throw new BackendCreationException(
                "Vulkan " + scope + " is missing required extensions: " + String.join(", ", missing),
                BackendCreationException.Reason.VULKAN_MISSING_EXTENSION, missing);
    }

    private static void ensureLoader() throws BackendCreationException {
        try {
            try { VK.getFunctionProvider(); }
            catch (IllegalStateException notReady) { VK.create(); }
            if (VK.getInstanceVersionSupported() <= 0) throw failure(
                    "Vulkan loader reports no supported instance API",
                    BackendCreationException.Reason.VULKAN_LOADER_MISSING);
        } catch (BackendCreationException error) {
            throw error;
        } catch (RuntimeException | LinkageError error) {
            throw failure("Vulkan loader unavailable: " + safeMessage(error),
                    BackendCreationException.Reason.VULKAN_LOADER_MISSING);
        }
    }

    private static int supportedApiVersion() {
        try { return VK.getInstanceVersionSupported(); }
        catch (RuntimeException | LinkageError ignored) { return VK10.VK_API_VERSION_1_0; }
    }

    private static void cleanup(VkDevice device, VkInstance instance, long surface,
                                VkPhysicalDeviceProperties properties) {
        if (device != null) try { VK10.vkDestroyDevice(device, null); } catch (Throwable ignored) { }
        if (properties != null) try { properties.free(); } catch (Throwable ignored) { }
        if (instance != null && surface != 0L) try { SdlVulkanWsiBridge.destroySurface(instance, surface); }
        catch (Throwable ignored) { }
        if (instance != null) try { VK10.vkDestroyInstance(instance, null); } catch (Throwable ignored) { }
    }

    private static Throwable append(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static BackendCreationException failure(String message, BackendCreationException.Reason reason) {
        return new BackendCreationException(message, reason);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private record DeviceCandidate(VkPhysicalDevice device,
                                  NativeVulkanQueueSelection.Selection queues,
                                  Set<String> extensions) { }
}
