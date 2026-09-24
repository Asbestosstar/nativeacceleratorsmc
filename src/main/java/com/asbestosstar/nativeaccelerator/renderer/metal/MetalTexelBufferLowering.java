package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.GpuFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lowers active native MSL texture buffers into SDL-GPU-compatible read-only storage buffers.
 * Inactive SPIR-V descriptors are discarded and the surviving storage-buffer table is compacted.
 */
final class MetalTexelBufferLowering {
    record Binding(int textureSlot, int storageSlot, GpuFormat format) {}
    record Result(String source, int[] physicalSlotsByLogical, int storageBufferCount) {
        int physicalSlot(int logical) {
            return logical >= 0 && logical < physicalSlotsByLogical.length ? physicalSlotsByLogical[logical] : -1;
        }
    }

    private static final Pattern TEXTURE_BUFFER_DECLARATION = Pattern.compile(
            "texture_buffer\\s*<\\s*int(?:\\s*,\\s*access::read)?\\s*>\\s+" +
            "([A-Za-z_][A-Za-z0-9_]*)\\s*\\[\\[texture\\((\\d+)\\)\\]\\]");

    private MetalTexelBufferLowering() {}

    static Result lower(String source, List<Binding> bindings, int uniformBufferCount, int logicalStorageCount) {
        int[] physical = new int[logicalStorageCount];
        Arrays.fill(physical, -1);
        if (bindings.isEmpty()) return new Result(source, physical, 0);

        List<Binding> active = new ArrayList<>();
        for (Binding binding : bindings) {
            if (binding.format() != GpuFormat.R8_SINT) {
                throw new UnsupportedOperationException(
                        "Metal texel-buffer lowering currently supports R8_SINT only; got " + binding.format());
            }
            if (hasTextureSlot(source, binding.textureSlot())) active.add(binding);
        }
        active.sort(Comparator.comparingInt(Binding::storageSlot));
        for (int i = 0; i < active.size(); i++) physical[active.get(i).storageSlot()] = i;

        String lowered = source;
        // Replace higher temporary texture slots first for deterministic source editing.
        List<Binding> replacementOrder = new ArrayList<>(active);
        replacementOrder.sort(Comparator.comparingInt(Binding::textureSlot).reversed());
        for (Binding binding : replacementOrder) {
            int physicalStorage = physical[binding.storageSlot()];
            lowered = lowerOne(lowered, binding, uniformBufferCount + physicalStorage);
        }
        return new Result(lowered, physical, active.size());
    }

    private static boolean hasTextureSlot(String source, int wanted) {
        Matcher matcher = TEXTURE_BUFFER_DECLARATION.matcher(source);
        while (matcher.find()) if (Integer.parseInt(matcher.group(2)) == wanted) return true;
        return false;
    }

    private static String lowerOne(String source, Binding binding, int mslBufferIndex) {
        Matcher matcher = TEXTURE_BUFFER_DECLARATION.matcher(source);
        String variable = null;
        StringBuffer declarations = new StringBuffer(source.length());
        while (matcher.find()) {
            int textureSlot = Integer.parseInt(matcher.group(2));
            if (textureSlot != binding.textureSlot()) {
                matcher.appendReplacement(declarations, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            if (variable != null) {
                throw new IllegalStateException("Multiple MSL texture buffers use temporary texture slot " + textureSlot);
            }
            variable = matcher.group(1);
            String replacement = "device const char* " + variable + " [[buffer(" + mslBufferIndex + ")]]";
            matcher.appendReplacement(declarations, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(declarations);
        if (variable == null) {
            throw new IllegalStateException(
                    "SPIRV-Cross did not emit active texture_buffer<int> for temporary texture slot "
                            + binding.textureSlot());
        }
        return replaceReadCalls(declarations.toString(), variable);
    }

    /** Rewrites name.read(expr) to int4(int(name[expr]), 0, 0, 1), preserving nested expressions. */
    private static String replaceReadCalls(String source, String variable) {
        String needle = variable + ".read(";
        StringBuilder out = new StringBuilder(source.length() + 64);
        int cursor = 0;
        while (true) {
            int start = source.indexOf(needle, cursor);
            if (start < 0) {
                out.append(source, cursor, source.length());
                break;
            }
            out.append(source, cursor, start);
            int expressionStart = start + needle.length();
            int depth = 1;
            int p = expressionStart;
            for (; p < source.length() && depth != 0; p++) {
                char c = source.charAt(p);
                if (c == '(') depth++;
                else if (c == ')') depth--;
            }
            if (depth != 0) throw new IllegalStateException("Unbalanced MSL texture-buffer read for " + variable);
            int expressionEnd = p - 1;
            String expression = source.substring(expressionStart, expressionEnd);
            out.append("int4(int(").append(variable).append('[').append(expression)
                    .append("]), 0, 0, 1)");
            cursor = p;
        }
        return out.toString();
    }
}

