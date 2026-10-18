package com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy;

import com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderPackSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;

/** Parses the deliberately small Native Accelerator shaderpack manifest. */
public final class ShaderpackManifestParser {
    public static final String PATH = "nativeaccelerator.shaderpack.json";
    public static final int FORMAT = 1;

    public ShaderpackManifest parse(ShaderPackSource source) {
        if (source == null) throw new IllegalArgumentException("source is null");
        String text = source.readText(PATH);
        if (text == null) throw new IllegalArgumentException("missing " + PATH);
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("manifest root must be an object");
            JsonObject root = parsed.getAsJsonObject();
            int format = requiredInt(root, "format");
            if (format != FORMAT) throw new IllegalArgumentException("unsupported manifest format: " + format);
            String id = requiredString(root, "id");
            String name = optionalString(root, "name", id);
            String language = requiredString(root, "language");
            String profile = requiredString(root, "profile");
            JsonObject programObject = requiredObject(root, "program");
            ShaderpackManifest.Program program = new ShaderpackManifest.Program(
                    requiredString(programObject, "vertex"),
                    requiredString(programObject, "fragment"),
                    stringMap(programObject.get("defines")));
            JsonObject passObject = requiredObject(root, "pass");
            ShaderpackManifest.Pass pass = new ShaderpackManifest.Pass(
                    requiredString(passObject, "type"),
                    requiredString(passObject, "output"),
                    stringList(passObject.get("inputs")));
            if (!"glsl".equalsIgnoreCase(language)) throw new IllegalArgumentException("only GLSL is supported");
            if (!"fullscreen-postprocess".equalsIgnoreCase(pass.type())) {
                throw new IllegalArgumentException("only fullscreen-postprocess passes are supported");
            }
            if (!"main".equals(pass.output())) throw new IllegalArgumentException("pass output must be main");
            if (pass.inputs().stream().anyMatch(value -> !"main".equals(value))) {
                throw new IllegalArgumentException("only main input is supported");
            }
            return new ShaderpackManifest(format, id, name, language, profile, program, pass);
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException) throw error;
            throw new IllegalArgumentException("invalid shaderpack manifest", error);
        }
    }

    private static JsonObject requiredObject(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("missing object: " + field);
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("missing string: " + field);
        }
        String result = value.getAsString().trim();
        if (result.isEmpty()) throw new IllegalArgumentException("blank string: " + field);
        return result;
    }

    private static String optionalString(JsonObject object, String field, String fallback) {
        JsonElement value = object.get(field);
        return value == null ? fallback : requiredString(object, field);
    }

    private static int requiredInt(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("missing number: " + field);
        }
        return value.getAsInt();
    }

    private static List<String> stringList(JsonElement value) {
        if (value == null) return List.of();
        if (!value.isJsonArray()) throw new IllegalArgumentException("array expected");
        List<String> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("array item must be a string");
            result.add(item.getAsString());
        }
        return result;
    }

    private static java.util.Map<String, String> stringMap(JsonElement value) {
        if (value == null) return java.util.Map.of();
        if (!value.isJsonObject()) throw new IllegalArgumentException("defines must be an object");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (var entry : value.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) throw new IllegalArgumentException("define must be scalar: " + entry.getKey());
            result.put(entry.getKey(), entry.getValue().getAsString());
        }
        return result;
    }
}
