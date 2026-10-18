package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses active post-preprocessor target comments without selecting inactive raw source branches. */
public final class ShaderpackRenderTargetParser {
    private static final Pattern DIRECTIVE = Pattern.compile("(?i)\\b(RENDERTARGETS|DRAWBUFFERS)\\s*:\\s*([0-9, ]+)");
    private static final int MAX_TARGETS = 16;

    public List<ShaderpackRenderTarget> parse(String preprocessedSource) {
        if (preprocessedSource == null) throw new IllegalArgumentException("source is null");
        Matcher matcher = DIRECTIVE.matcher(preprocessedSource);
        List<ShaderpackRenderTarget> result = new ArrayList<>();
        while (matcher.find()) {
            String[] values = matcher.group(2).split(",");
            if (values.length > MAX_TARGETS) throw new IllegalArgumentException("too many render targets");
            for (String value : values) {
                String trimmed = value.trim();
                if (trimmed.isEmpty()) continue;
                int attachment;
                try { attachment = Integer.parseInt(trimmed); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("invalid render target: " + trimmed, ex); }
                if (attachment < 0 || attachment > 31) throw new IllegalArgumentException("render target out of range: " + attachment);
                result.add(new ShaderpackRenderTarget(attachment, matcher.group(1).toUpperCase(java.util.Locale.ROOT)));
            }
        }
        return List.copyOf(result);
    }
}
