package com.asbestosstar.nativeaccelerator.renderer.universal.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Loads and validates the small cross-backend URD demonstration material. */
public record TriangleMaterial(ShaderSourceSpec vertex, ShaderSourceSpec fragment) {
    private static final String ROOT = "/nativeaccelerator/shaders/urd/";
    private static final String MANIFEST = ROOT + "material.json";

    public static TriangleMaterial load(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        JsonObject manifest;
        try (InputStream stream = loader.getResourceAsStream(MANIFEST.substring(1))) {
            if (stream == null) throw new IllegalStateException("missing URD material manifest: " + MANIFEST);
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                manifest = JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("unable to read URD material manifest", ex);
        }

        String language = requiredString(manifest, "language");
        if (!"glsl".equalsIgnoreCase(language)) throw new IllegalStateException("URD material is not GLSL");
        String version = requiredString(manifest, "profile");
        JsonObject stages = requiredObject(manifest, "stages");
        String vertexPath = requiredString(stages, "vertex");
        String fragmentPath = requiredString(stages, "fragment");
        String coordinate = requiredString(manifest, "coordinateConvention");
        if (!"clip-space-z-minus-one-to-one".equals(coordinate)) {
            throw new IllegalStateException("unsupported URD coordinate convention: " + coordinate);
        }
        validateVertexInputs(manifest);

        String vertexSource = readText(loader, ROOT + vertexPath);
        String fragmentSource = readText(loader, ROOT + fragmentPath);
        validateVersion(vertexSource, version, vertexPath);
        validateVersion(fragmentSource, version, fragmentPath);
        validateStageSource(vertexSource, true, vertexPath);
        validateStageSource(fragmentSource, false, fragmentPath);
        return new TriangleMaterial(
                new ShaderSourceSpec(vertexSource, "nativeaccelerator:urd/" + vertexPath,
                        version, GlslProfile.VULKAN, "vertex"),
                new ShaderSourceSpec(fragmentSource, "nativeaccelerator:urd/" + fragmentPath,
                        version, GlslProfile.VULKAN, "fragment"));
    }

    public TriangleMaterial forProfile(GlslProfile profile) {
        return new TriangleMaterial(
                withProfile(vertex, profile), withProfile(fragment, profile));
    }

    private static ShaderSourceSpec withProfile(ShaderSourceSpec source, GlslProfile profile) {
        return new ShaderSourceSpec(source.source(), source.sourceName(), source.version(), profile, source.stage());
    }

    private static void validateVertexInputs(JsonObject manifest) {
        JsonArray inputs = manifest.getAsJsonArray("vertexInputs");
        if (inputs == null || inputs.size() != 2) throw new IllegalStateException("URD triangle input manifest is invalid");
        if (inputs.get(0).getAsJsonObject().get("location").getAsInt() != 0
                || !"vec2".equals(inputs.get(0).getAsJsonObject().get("type").getAsString())
                || inputs.get(1).getAsJsonObject().get("location").getAsInt() != 1
                || !"vec3".equals(inputs.get(1).getAsJsonObject().get("type").getAsString())) {
            throw new IllegalStateException("URD triangle vertex input manifest is invalid");
        }
    }

    private static void validateVersion(String source, String expected, String name) {
        String detected = new ShaderSourceSpec(source, name, null, GlslProfile.VULKAN, "vertex").version();
        if (!expected.equals(detected)) throw new IllegalStateException("GLSL version mismatch in " + name);
    }

    private static void validateStageSource(String source, boolean vertex, String name) {
        String required = vertex ? "gl_Position" : "out_color";
        String forbidden = vertex ? "out_color" : "gl_Position";
        if (!source.contains(required) || source.contains(forbidden)) {
            throw new IllegalStateException("URD " + (vertex ? "vertex" : "fragment")
                    + " shader stage appears reversed in " + name);
        }
    }

    private static String readText(ClassLoader loader, String path) {
        try (InputStream stream = loader.getResourceAsStream(path.substring(1))) {
            if (stream == null) throw new IllegalStateException("missing URD shader: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("unable to read URD shader: " + path, ex);
        }
    }

    private static String requiredString(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonPrimitive()) {
            throw new IllegalStateException("missing URD material field: " + name);
        }
        return object.get(name).getAsString();
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonObject()) {
            throw new IllegalStateException("missing URD material object: " + name);
        }
        return object.getAsJsonObject(name);
    }
}
