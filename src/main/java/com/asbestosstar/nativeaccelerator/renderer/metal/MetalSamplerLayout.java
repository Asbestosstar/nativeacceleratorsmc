package com.asbestosstar.nativeaccelerator.renderer.metal;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compacts active sampled-texture/sampler slots in generated MSL to SDL's required 0..N-1 table. */
final class MetalSamplerLayout {
    private static final Pattern TEXTURE = Pattern.compile("\\[\\[\\s*texture\\((\\d+)\\)\\s*\\]\\]");
    private static final Pattern SAMPLER = Pattern.compile("\\[\\[\\s*sampler\\((\\d+)\\)\\s*\\]\\]");

    record Result(String source, int[] physicalSlotsByLogical, int samplerCount, int[] activeLogicalSlots) {
        int physicalSlot(int logical) {
            return logical >= 0 && logical < physicalSlotsByLogical.length ? physicalSlotsByLogical[logical] : -1;
        }
    }

    private MetalSamplerLayout() {}

    static Result lower(String source, String entryPoint, int logicalSamplerCount) {
        int[] physical = new int[logicalSamplerCount];
        Arrays.fill(physical, -1);
        if (logicalSamplerCount == 0) return new Result(source, physical, 0, new int[0]);

        MetalUniformPacking.Entry entry = MetalUniformPacking.entry(source, entryPoint);
        List<String> parameters = MetalUniformPacking.splitParameters(entry.parameterText());
        boolean[] textureActive = new boolean[logicalSamplerCount];
        boolean[] samplerActive = new boolean[logicalSamplerCount];

        for (String parameter : parameters) {
            Matcher t = TEXTURE.matcher(parameter);
            if (t.find()) {
                int slot = Integer.parseInt(t.group(1));
                // Texture-buffer temporaries are allocated at samplerCount+, so ignore them here.
                if (slot >= 0 && slot < logicalSamplerCount) textureActive[slot] = true;
            }
            Matcher s = SAMPLER.matcher(parameter);
            if (s.find()) {
                int slot = Integer.parseInt(s.group(1));
                if (slot >= 0 && slot < logicalSamplerCount) samplerActive[slot] = true;
            }
        }

        int activeCount = 0;
        for (int i = 0; i < logicalSamplerCount; i++) {
            if (textureActive[i] != samplerActive[i]) {
                throw new IllegalStateException(
                        "Generated MSL combined sampler slot " + i + " has texture/sampler activity mismatch");
            }
            if (textureActive[i]) physical[i] = activeCount++;
        }

        int[] activeSlots = new int[activeCount];
        for (int i = 0, p = 0; i < logicalSamplerCount; i++) if (physical[i] >= 0) activeSlots[p++] = i;
        if (activeCount == logicalSamplerCount) {
            boolean identity = true;
            for (int i = 0; i < logicalSamplerCount; i++) identity &= physical[i] == i;
            if (identity) return new Result(source, physical, activeCount, activeSlots);
        }

        String rewritten = rewrite(source, entry, parameters, physical, logicalSamplerCount);
        return new Result(rewritten, physical, activeCount, activeSlots);
    }

    private static String rewrite(
            String source,
            MetalUniformPacking.Entry entry,
            List<String> parameters,
            int[] physical,
            int logicalSamplerCount) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < parameters.size(); i++) {
            String parameter = parameters.get(i).trim();
            Matcher t = TEXTURE.matcher(parameter);
            if (t.find()) {
                int logical = Integer.parseInt(t.group(1));
                if (logical >= 0 && logical < logicalSamplerCount && physical[logical] >= 0) {
                    parameter = MetalUniformPacking.replaceAttributeIndex(parameter, "texture", physical[logical]);
                }
            }
            Matcher s = SAMPLER.matcher(parameter);
            if (s.find()) {
                int logical = Integer.parseInt(s.group(1));
                if (logical >= 0 && logical < logicalSamplerCount && physical[logical] >= 0) {
                    parameter = MetalUniformPacking.replaceAttributeIndex(parameter, "sampler", physical[logical]);
                }
            }
            if (i != 0) body.append(",\n    ");
            body.append(parameter);
        }
        return source.substring(0, entry.openParen() + 1)
                + (parameters.isEmpty() ? "" : "\n    " + body + "\n")
                + source.substring(entry.closeParen());
    }
}

