package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.GpuFormat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lowers the native MSL texture-buffer form emitted by SPIRV-Cross into an SDL-GPU-compatible
 * read-only storage buffer.
 *
 * <p>SDL's Metal shader ABI has no texel-buffer binding category, but it does expose graphics
 * storage buffers. Minecraft 26.3's CloudFaces resource is R8_SINT and is only fetched by integer
 * element index, so the native {@code texture_buffer<int>} can be represented losslessly as a
 * {@code device const char*}. Reads are expanded back to the four-component integer value that a
 * texel fetch returns.</p>
 */
final class MetalTexelBufferLowering {
    record Binding(int textureSlot, int storageSlot, int mslBufferIndex, GpuFormat format) {}

    private static final Pattern TEXTURE_BUFFER_DECLARATION = Pattern.compile(
            "texture_buffer\\s*<\\s*int(?:\\s*,\\s*access::read)?\\s*>\\s+" +
            "([A-Za-z_][A-Za-z0-9_]*)\\s*\\[\\[texture\\((\\d+)\\)\\]\\]");

    private MetalTexelBufferLowering() {}

    static String lower(String source, List<Binding> bindings) {
        if (bindings.isEmpty()) return source;

        String lowered = source;
        // Work from the highest temporary texture slot down simply to make diagnostics deterministic.
        List<Binding> ordered = new ArrayList<>(bindings);
        ordered.sort(Comparator.comparingInt(Binding::textureSlot).reversed());
        for (Binding binding : ordered) {
            if (binding.format() != GpuFormat.R8_SINT) {
                throw new UnsupportedOperationException(
                        "Metal texel-buffer lowering currently supports R8_SINT only; got " + binding.format());
            }
            lowered = lowerOne(lowered, binding);
        }
        return lowered;
    }

    private static String lowerOne(String source, Binding binding) {
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
            String replacement = "device const char* " + variable + " [[buffer(" + binding.mslBufferIndex() + ")]]";
            matcher.appendReplacement(declarations, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(declarations);
        if (variable == null) {
            throw new IllegalStateException(
                    "SPIRV-Cross did not emit the expected native texture_buffer<int> declaration for temporary texture slot "
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
            if (depth != 0) {
                throw new IllegalStateException("Unbalanced MSL texture-buffer read for " + variable);
            }
            int expressionEnd = p - 1;
            String expression = source.substring(expressionStart, expressionEnd);
            out.append("int4(int(").append(variable).append('[').append(expression)
                    .append("]), 0, 0, 1)");
            cursor = p;
        }
        return out.toString();
    }
}
