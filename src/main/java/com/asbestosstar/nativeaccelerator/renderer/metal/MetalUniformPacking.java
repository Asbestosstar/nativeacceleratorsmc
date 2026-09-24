package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes SPIRV-Cross MSL uniform-buffer arguments to SDL GPU's Metal ABI.
 *
 * <p>SDL requires the active uniform-buffer portion of the MSL [[buffer]] table to begin at zero
 * and contain no gaps. SPIRV-Cross may optimize away descriptors or push constants after Native
 * Accelerator has already assigned logical slots, so the logical RenderPearl layout is not a safe
 * substitute for the generated MSL entry signature.</p>
 *
 * <p>Up to four active uniform resources are compacted to consecutive physical slots 0..N-1. If
 * more than four survive, the first three active resources stay direct and the remaining resources
 * are packed into one byte buffer at physical slot 3. CPU-side routing consumes the exact same map.</p>
 */
final class MetalUniformPacking {
    static final int DIRECT_SLOTS = 3;
    static final int PACKED_SLOT = 3;
    static final int PACK_STRIDE = 512;
    static final int MAX_PACK_BYTES = 4096;

    private static final Pattern BUFFER_PARAMETER = Pattern.compile(
            "\\bconstant\\s+([A-Za-z_][A-Za-z0-9_:<>]*)\\s*&\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*"
                    + "\\[\\[\\s*buffer\\((\\d+)\\)\\s*\\]\\]");

    record Result(
            String source,
            int[] activeLogicalSlots,
            int[] physicalSlotsByLogical,
            int[] packedOffsetsByLogical,
            int physicalUniformCount,
            int packedBytes) {
        boolean isActiveLogicalSlot(int logicalSlot) {
            return logicalSlot >= 0
                    && logicalSlot < physicalSlotsByLogical.length
                    && (physicalSlotsByLogical[logicalSlot] >= 0 || packedOffsetsByLogical[logicalSlot] >= 0);
        }
        int physicalSlot(int logicalSlot) {
            return logicalSlot >= 0 && logicalSlot < physicalSlotsByLogical.length
                    ? physicalSlotsByLogical[logicalSlot] : -1;
        }
        int packedOffset(int logicalSlot) {
            return logicalSlot >= 0 && logicalSlot < packedOffsetsByLogical.length
                    ? packedOffsetsByLogical[logicalSlot] : -1;
        }
        boolean packingApplied() { return packedBytes > 0; }
    }

    private MetalUniformPacking() {}

    static Result lower(String source, String entryPoint, int logicalUniformCount) {
        int[] physical = filled(logicalUniformCount, -1);
        int[] packedOffsets = filled(logicalUniformCount, -1);
        if (logicalUniformCount == 0) {
            return new Result(source, new int[0], physical, packedOffsets, 0, 0);
        }

        Entry entry = entry(source, entryPoint);
        List<String> parameters = splitParameters(entry.parameterText());
        List<PackedParam> active = new ArrayList<>();
        for (String parameter : parameters) {
            Matcher matcher = BUFFER_PARAMETER.matcher(parameter.trim());
            if (!matcher.find()) continue;
            int logical = Integer.parseInt(matcher.group(3));
            if (logical < 0 || logical >= logicalUniformCount) continue;
            active.add(new PackedParam(matcher.group(1), matcher.group(2), logical));
        }

        int[] activeSlots = active.stream().mapToInt(PackedParam::logicalSlot).distinct().sorted().toArray();
        if (activeSlots.length == 0) {
            return new Result(source, activeSlots, physical, packedOffsets, 0, 0);
        }

        // SDL's uniform slots are ordered by RenderPearl logical slot, not by textual MSL parameter order.
        if (activeSlots.length <= 4) {
            for (int i = 0; i < activeSlots.length; i++) physical[activeSlots[i]] = i;
            String rewritten = rewriteDirectBufferIndices(source, entry, parameters, physical, logicalUniformCount);
            return new Result(rewritten, activeSlots, physical, packedOffsets, activeSlots.length, 0);
        }

        // More than four active UBO/push resources: first three direct, rest packed into physical slot 3.
        for (int i = 0; i < DIRECT_SLOTS; i++) physical[activeSlots[i]] = i;
        int packedCount = activeSlots.length - DIRECT_SLOTS;
        int packedBytes = Math.multiplyExact(packedCount, PACK_STRIDE);
        if (packedBytes > MAX_PACK_BYTES) {
            throw new UnsupportedOperationException(
                    "Metal uniform packing would require " + packedBytes + " bytes; limit is " + MAX_PACK_BYTES);
        }
        for (int i = DIRECT_SLOTS; i < activeSlots.length; i++) {
            packedOffsets[activeSlots[i]] = (i - DIRECT_SLOTS) * PACK_STRIDE;
        }

        List<String> rewritten = new ArrayList<>(parameters.size());
        List<PackedParam> packed = new ArrayList<>();
        int packedInsertion = -1;
        for (String parameter : parameters) {
            Matcher matcher = BUFFER_PARAMETER.matcher(parameter.trim());
            if (!matcher.find()) {
                rewritten.add(parameter.trim());
                continue;
            }
            int logical = Integer.parseInt(matcher.group(3));
            if (logical < 0 || logical >= logicalUniformCount || !contains(activeSlots, logical)) {
                rewritten.add(parameter.trim());
                continue;
            }
            int direct = physical[logical];
            if (direct >= 0) {
                rewritten.add(replaceAttributeIndex(parameter.trim(), "buffer", direct));
            } else {
                if (packedInsertion < 0) packedInsertion = rewritten.size();
                packed.add(new PackedParam(matcher.group(1), matcher.group(2), logical));
            }
        }

        if (packedInsertion < 0) packedInsertion = rewritten.size();
        rewritten.add(packedInsertion,
                "constant uchar* _naPackedUniforms [[buffer(" + PACKED_SLOT + ")]]");

        StringBuilder locals = new StringBuilder();
        locals.append("\n    // Native Accelerator: pack active overflow uniforms into SDL physical slot 3.\n");
        for (PackedParam param : packed) {
            int offset = packedOffsets[param.logicalSlot()];
            locals.append("    constant ").append(param.type()).append("& ").append(param.variable())
                    .append(" = *reinterpret_cast<constant ").append(param.type())
                    .append("*>(_naPackedUniforms + ").append(offset).append("u);\n");
        }

        String rebuiltParams = String.join(",\n    ", rewritten);
        String result = source.substring(0, entry.openParen() + 1)
                + (rewritten.isEmpty() ? "" : "\n    " + rebuiltParams + "\n")
                + source.substring(entry.closeParen(), entry.bodyOpen() + 1)
                + locals
                + source.substring(entry.bodyOpen() + 1);
        return new Result(result, activeSlots, physical, packedOffsets, 4, packedBytes);
    }

    private static String rewriteDirectBufferIndices(
            String source, Entry entry, List<String> parameters, int[] physical, int logicalUniformCount) {
        List<String> rewritten = new ArrayList<>(parameters.size());
        for (String parameter : parameters) {
            Matcher matcher = BUFFER_PARAMETER.matcher(parameter.trim());
            if (!matcher.find()) {
                rewritten.add(parameter.trim());
                continue;
            }
            int logical = Integer.parseInt(matcher.group(3));
            if (logical >= 0 && logical < logicalUniformCount && physical[logical] >= 0) {
                rewritten.add(replaceAttributeIndex(parameter.trim(), "buffer", physical[logical]));
            } else {
                rewritten.add(parameter.trim());
            }
        }
        String rebuiltParams = String.join(",\n    ", rewritten);
        return source.substring(0, entry.openParen() + 1)
                + (rewritten.isEmpty() ? "" : "\n    " + rebuiltParams + "\n")
                + source.substring(entry.closeParen());
    }

    static Entry entry(String source, String entryPoint) {
        int name = findEntryName(source, entryPoint);
        int open = skipWhitespaceTo(source, name + entryPoint.length(), '(');
        int close = matchingParen(source, open);
        int body = skipWhitespaceTo(source, close + 1, '{');
        return new Entry(open, close, body, source.substring(open + 1, close));
    }

    static String replaceAttributeIndex(String parameter, String attribute, int newIndex) {
        return parameter.replaceFirst(
                "\\[\\[\\s*" + Pattern.quote(attribute) + "\\(\\d+\\)\\s*\\]\\]",
                "[[" + attribute + "(" + newIndex + ")]]");
    }

    static List<String> splitParameters(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int angle = 0, paren = 0, bracket = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') angle++;
            else if (c == '>') angle = Math.max(0, angle - 1);
            else if (c == '(') paren++;
            else if (c == ')') paren = Math.max(0, paren - 1);
            else if (c == '[') bracket++;
            else if (c == ']') bracket = Math.max(0, bracket - 1);
            else if (c == ',' && angle == 0 && paren == 0 && bracket == 0) {
                String part = text.substring(start, i).trim();
                if (!part.isEmpty()) out.add(part);
                start = i + 1;
            }
        }
        String last = text.substring(start).trim();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    private static int findEntryName(String source, String entryPoint) {
        Pattern p = Pattern.compile("\\b(?:vertex|fragment)\\b[\\s\\S]{0,2048}?\\b"
                + Pattern.quote(entryPoint) + "\\s*\\(");
        Matcher m = p.matcher(source);
        if (!m.find()) throw new IllegalStateException("Could not locate MSL entry point " + entryPoint);
        int local = source.substring(m.start(), m.end()).lastIndexOf(entryPoint);
        return m.start() + local;
    }

    private static int skipWhitespaceTo(String source, int start, char wanted) {
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (c == wanted) return i;
            throw new IllegalStateException("Expected '" + wanted + "' near MSL entry point, found '" + c + "'");
        }
        throw new IllegalStateException("Unexpected end of MSL source while looking for '" + wanted + "'");
    }

    private static int matchingParen(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        throw new IllegalStateException("Unbalanced MSL entry-point parameter list");
    }

    private static boolean contains(int[] values, int value) {
        return Arrays.binarySearch(values, value) >= 0;
    }

    private static int[] filled(int n, int value) {
        int[] result = new int[n];
        Arrays.fill(result, value);
        return result;
    }

    record Entry(int openParen, int closeParen, int bodyOpen, String parameterText) {}
    private record PackedParam(String type, String variable, int logicalSlot) {}
}

