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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Clean-room, best-effort diagnostics for the extensions passed to vkCreateInstance. */
public final class VulkanInstanceExtensionDiagnostics {
    private VulkanInstanceExtensionDiagnostics() {}

    public static String format(VkInstanceCreateInfo createInfo) {
        List<String> requested = names(createInfo == null ? null : createInfo.ppEnabledExtensionNames());
        try {
            Set<String> supported = enumerateSupported();
            return format(requested, supported, null);
        } catch (Throwable failure) {
            return format(requested, null, failure.getClass().getSimpleName());
        }
    }

    /**
     * Repairs a known Solaris/LWJGL failure mode where a native extension name is truncated before
     * RenderPearl passes it to vkCreateInstance. The caller owns {@code stack} and must keep it open
     * until vkCreateInstance returns; the replacement pointers are stack-backed.
     */
    public static boolean repairTruncatedExtensions(VkInstanceCreateInfo createInfo, MemoryStack stack) {
        if (createInfo == null || stack == null) return false;
        List<String> requested = names(createInfo.ppEnabledExtensionNames());
        if (requested.isEmpty()) return false;
        Set<String> supported;
        try {
            supported = enumerateSupported(stack);
        } catch (Throwable ignored) {
            return false;
        }
        List<String> repaired = new ArrayList<>(requested.size());
        boolean changed = false;
        for (String name : requested) {
            String replacement = shortestSupportedPrefix(name, supported);
            repaired.add(replacement == null ? name : replacement);
            changed |= replacement != null;
        }
        if (!changed) return false;
        try {
            PointerBuffer pointers = stack.mallocPointer(repaired.size());
            for (String name : repaired) pointers.put(stack.UTF8(name));
            pointers.flip();
            createInfo.ppEnabledExtensionNames(pointers);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Set<String> enumerateSupported() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return enumerateSupported(stack);
        }
    }

    private static Set<String> enumerateSupported(MemoryStack stack) {
        var count = stack.callocInt(1);
        int first = VK12.vkEnumerateInstanceExtensionProperties((String) null, count, null);
        if (first != 0) throw new IllegalStateException("enumeration-result=" + first);
        VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0), stack);
        int second = VK12.vkEnumerateInstanceExtensionProperties((String) null, count, properties);
        if (second != 0) throw new IllegalStateException("enumeration-result=" + second);
        Set<String> supported = new HashSet<>();
        for (int i = 0; i < properties.capacity(); i++) {
            supported.add(properties.get(i).extensionNameString());
        }
        return supported;
    }

    private static String shortestSupportedPrefix(String requested, Set<String> supported) {
        if (requested == null || requested.isBlank()) return null;
        // Only repair the two exact truncated names observed in the affected Solaris/LWJGL path.
        // Never guess a replacement for an arbitrary extension: a prefix is not an extension name.
        String expected = switch (requested) {
            case "VK_KHR_s" -> "VK_KHR_surface";
            case "VK_KHR_xlib_s" -> "VK_KHR_xlib_surface";
            default -> null;
        };
        // The affected Solaris/LWJGL path truncates the names while reading both SDL and the
        // extension-property structures, so the diagnostic copy of `supported` is truncated too.
        // These are standardized names, not guessed device requirements.
        return expected;
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
            if (address != 0L) {
                // Vulkan extension names are limited by VK_MAX_EXTENSION_NAME_SIZE (256). Use an
                // explicit bound rather than an unbounded native scan so a malformed driver cannot
                // read past the advertised name storage.
                ByteBuffer bytes = MemoryUtil.memByteBuffer(address, 256);
                int length = 0;
                while (length < bytes.remaining() && bytes.get(bytes.position() + length) != 0) length++;
                names.add(StandardCharsets.UTF_8.decode(bytes.slice(0, length)).toString());
            }
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
