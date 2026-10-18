package com.asbestosstar.nativeaccelerator.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain-main validation for shader-dialogue locale keys and format arguments. */
public final class LegacyShadersLocaleTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?([a-zA-Z])");

    public static void main(String[] args) throws Exception {
        JsonObject english = read("en_us.json");
        for (String locale : new String[]{"es_ar", "es_cl", "es_co", "es_es", "es_mx", "es_uy", "es_ve"}) {
            JsonObject translated = read(locale + ".json");
            for (var entry : english.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("nativeaccelerator.shaderpacks.")
                        && !key.equals("nativeaccelerator.legacyShaders")
                        && !key.startsWith("nativeaccelerator.shaderTechnology.")) continue;
                if (!translated.has(key)) throw new AssertionError(locale + " is missing " + key);
                String expected = placeholderSignature(entry.getValue().getAsString());
                String actual = placeholderSignature(translated.get(key).getAsString());
                if (!expected.equals(actual)) throw new AssertionError(locale + " placeholder mismatch for " + key);
            }
        }
        System.out.println("LegacyShadersLocaleTest: PASS");
    }

    private static JsonObject read(String locale) throws Exception {
        String resource = "/assets/nativeaccelerator/lang/" + locale;
        try (var stream = LegacyShadersLocaleTest.class.getResourceAsStream(resource)) {
            if (stream == null) throw new AssertionError("missing locale resource " + resource);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static String placeholderSignature(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder signature = new StringBuilder();
        int implicitIndex = 1;
        while (matcher.find()) {
            if (signature.length() > 0) signature.append(',');
            int index = matcher.group(1) == null ? implicitIndex++ : Integer.parseInt(matcher.group(1));
            signature.append(index).append('$').append(matcher.group(2));
        }
        return signature.toString();
    }
}
