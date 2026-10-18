package com.asbestosstar.nativeaccelerator.renderer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Clean-room, best-effort diagnostics for the extensions passed to vkCreateInstance. */
public final class VulkanInstanceExtensionDiagnostics {
    private VulkanInstanceExtensionDiagnostics() {}

    public static String format(VkInstanceCreateInfo createInfo) {
        List<String> requested = names(createInfo == null ? null : createInfo.ppEnabledExtensionNames());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var count = stack.callocInt(1);
            int first = VK12.vkEnumerateInstanceExtensionProperties((String) null, count, null);
            if (first != 0) {
                return format(requested, null, "enumeration-result=" + first);
            }
            VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0), stack);
            int second = VK12.vkEnumerateInstanceExtensionProperties((String) null, count, properties);
            if (second != 0) {
                return format(requested, null, "enumeration-result=" + second);
            }
            Set<String> supported = new HashSet<>();
            for (int i = 0; i < properties.capacity(); i++) {
                supported.add(properties.get(i).extensionNameString());
            }
            return format(requested, supported, null);
        } catch (Throwable failure) {
            return format(requested, null, failure.getClass().getSimpleName());
        }
    }

    static String format(List<String> requested, Set<String> supported, String unavailableReason) {
        List<String> requestedValues = normalized(requested);
        if (supported == null) {
            return "requestedInstanceExtensions=" + requestedValues
                    + " supportedInstanceExtensions=<unavailable>"
                    + " missingInstanceExtensions=<unknown>"
                    + (unavailableReason == null ? "" : " reason=" + unavailableReason);
        }
        List<String> supportedValues = normalized(supported);
        Set<String> supportedSet = new HashSet<>(supportedValues);
        List<String> missing = new ArrayList<>();
        for (String name : requestedValues) {
            if (!supportedSet.contains(name)) missing.add(name);
        }
        return "requestedInstanceExtensions=" + requestedValues
                + " supportedInstanceExtensions=" + supportedValues
                + " missingInstanceExtensions=" + missing;
    }

    private static List<String> names(PointerBuffer pointers) {
        if (pointers == null) return List.of();
        List<String> names = new ArrayList<>(pointers.remaining());
        for (int i = pointers.position(); i < pointers.limit(); i++) {
            long address = pointers.get(i);
            if (address != MemoryUtil.NULL) names.add(MemoryUtil.memUTF8(address));
        }
        return names;
    }

    private static List<String> normalized(Iterable<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) result.add(value.trim());
        }
        result.sort(Comparator.naturalOrder());
        List<String> unique = new ArrayList<>(result.size());
        String previous = null;
        for (String value : result) {
            if (!value.equals(previous)) unique.add(value);
            previous = value;
        }
        return List.copyOf(unique);
    }
}
