package com.asbestosstar.nativeaccelerator.client;

import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.cuboid.CuboidModelElement;
import net.minecraft.client.resources.model.cuboid.CuboidRotation;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.cuboid.UnbakedCuboidGeometry;
import net.minecraft.client.resources.model.geometry.UnbakedGeometry;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Allocation-light streaming parser for ordinary cuboid model JSON.
 *
 * <p>{@link CuboidModel#fromStream(Reader)} first materializes a Gson JsonElement tree and then walks it
 * through several reflection-driven type adapters.  Model files are tiny and numerous, so those temporary
 * objects are expensive.  This parser reads the same vanilla fields directly into the final model records.
 * Callers fall back to {@code CuboidModel.fromStream} when an unsupported shape is encountered.</p>
 */
public final class FastCuboidModelDecoder {
    private FastCuboidModelDecoder() {}

    public static CuboidModel parse(Reader input) throws IOException {
        long started = ModelPipelineProfiler.start();
        long cpuStarted = ModelPipelineProfiler.startThreadCpu();
        JsonReader reader = new JsonReader(input);
        reader.setLenient(false);

        UnbakedGeometry geometry = null;
        UnbakedModel.GuiLight guiLight = null;
        Boolean ambientOcclusion = null;
        ItemTransforms transforms = null;
        TextureSlots.Data textures = TextureSlots.Data.EMPTY;
        Identifier parent = null;

        require(reader.peek() == JsonToken.BEGIN_OBJECT, "Model root must be an object");
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "elements" -> geometry = parseElements(reader);
                case "gui_light" -> guiLight = UnbakedModel.GuiLight.getByName(reader.nextString());
                case "ambientocclusion" -> ambientOcclusion = reader.nextBoolean();
                case "display" -> transforms = parseTransforms(reader);
                case "textures" -> textures = parseTextures(reader);
                case "parent" -> {
                    String parentName = reader.nextString();
                    parent = parentName.isEmpty() ? null : IdentifierInterner.parse(parentName);
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new JsonParseException("Did not consume entire model document");

        CuboidModel result = new CuboidModel(geometry, guiLight, ambientOcclusion, transforms, textures, parent);
        ModelPipelineProfiler.end("raw-model.fast.decode", started);
        ModelPipelineProfiler.endThreadCpu("raw-model.fast.decode", cpuStarted);
        return result;
    }

    private static UnbakedCuboidGeometry parseElements(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "'elements' must be an array");
        ArrayList<CuboidModelElement> elements = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) elements.add(parseElement(reader));
        reader.endArray();
        return new UnbakedCuboidGeometry(List.copyOf(elements));
    }

    private static CuboidModelElement parseElement(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "Model element must be an object");
        Vector3f from = null;
        Vector3f to = null;
        Map<Direction, CuboidFace> faces = null;
        CuboidRotation rotation = null;
        Direction shadeDirection = null;
        int lightEmission = 0;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "from" -> from = parseVec3(reader, "from");
                case "to" -> to = parseVec3(reader, "to");
                case "faces" -> faces = parseFaces(reader);
                case "rotation" -> rotation = parseElementRotation(reader);
                case "shade_direction_override" -> {
                    String name = reader.nextString();
                    shadeDirection = Direction.byName(name);
                    if (shadeDirection == null) throw new JsonParseException("Unknown shade direction override: " + name);
                }
                case "light_emission" -> {
                    require(reader.peek() == JsonToken.NUMBER,
                            "Expected 'light_emission' to be an Integer between (inclusive) 0 and 15");
                    lightEmission = reader.nextInt();
                    if (lightEmission < 0 || lightEmission > 15) {
                        throw new JsonParseException("Expected 'light_emission' to be an Integer between (inclusive) 0 and 15");
                    }
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();

        require(from != null, "Element missing 'from'");
        require(to != null, "Element missing 'to'");
        require(faces != null && !faces.isEmpty(), "Expected between 1 and 6 unique faces, got 0");
        validateExtent(from, "from");
        validateExtent(to, "to");
        return new CuboidModelElement(from, to, faces, rotation, shadeDirection, lightEmission);
    }

    private static Map<Direction, CuboidFace> parseFaces(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "'faces' must be an object");
        EnumMap<Direction, CuboidFace> faces = new EnumMap<>(Direction.class);
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            Direction direction = Direction.byName(name);
            if (direction == null) throw new JsonParseException("Unknown facing: " + name);
            faces.put(direction, parseFace(reader));
        }
        reader.endObject();
        return faces;
    }

    private static CuboidFace parseFace(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "Face must be an object");
        Direction cull = null;
        int tint = -1;
        String texture = null;
        CuboidFace.UVs uvs = null;
        com.mojang.math.Quadrant rotation = com.mojang.math.Quadrant.R0;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "cullface" -> cull = Direction.byName(reader.nextString());
                case "tintindex" -> tint = reader.nextInt();
                case "texture" -> texture = reader.nextString();
                case "uv" -> {
                    float[] uv = parseFloatArray(reader, 4, "uv");
                    uvs = new CuboidFace.UVs(uv[0], uv[1], uv[2], uv[3]);
                }
                case "rotation" -> rotation = quadrant(reader.nextInt());
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        require(texture != null, "Face missing 'texture'");
        return new CuboidFace(cull, tint, texture, uvs, rotation);
    }

    private static CuboidRotation parseElementRotation(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "Element rotation must be an object");
        Vector3f origin = null;
        String axisName = null;
        Float angle = null;
        float x = 0f, y = 0f, z = 0f;
        boolean sawX = false, sawY = false, sawZ = false;
        boolean rescale = false;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "origin" -> origin = parseVec3(reader, "origin");
                case "axis" -> axisName = reader.nextString();
                case "angle" -> angle = (float) reader.nextDouble();
                case "x" -> { x = (float) reader.nextDouble(); sawX = true; }
                case "y" -> { y = (float) reader.nextDouble(); sawY = true; }
                case "z" -> { z = (float) reader.nextDouble(); sawZ = true; }
                case "rescale" -> rescale = reader.nextBoolean();
                default -> reader.skipValue();
            }
        }
        reader.endObject();

        require(origin != null, "Element rotation missing 'origin'");
        origin.mul(0.0625f);
        CuboidRotation.RotationValue value;
        if (axisName != null || angle != null) {
            require(axisName != null && angle != null, "Rotation requires both 'axis' and 'angle'");
            Direction.Axis axis = Direction.Axis.byName(axisName.toLowerCase(Locale.ROOT));
            if (axis == null) throw new JsonParseException("Invalid rotation axis: " + axisName);
            value = new CuboidRotation.SingleAxisRotation(axis, angle);
        } else if (sawX || sawY || sawZ) {
            value = new CuboidRotation.EulerXYZRotation(x, y, z);
        } else {
            throw new JsonParseException("Missing rotation value, expected either 'axis' and 'angle' or 'x', 'y' and 'z'");
        }
        return new CuboidRotation(origin, value, rescale);
    }

    private static TextureSlots.Data parseTextures(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "'textures' must be an object");
        TextureSlots.Data.Builder builder = new TextureSlots.Data.Builder();
        reader.beginObject();
        while (reader.hasNext()) {
            String slot = reader.nextName();
            if (reader.peek() == JsonToken.STRING) {
                String value = reader.nextString();
                if (value.startsWith("#")) builder.addReference(slot, value.substring(1));
                else builder.addTexture(slot, new Material(IdentifierInterner.parse(value)));
            } else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                Identifier sprite = null;
                boolean forceTranslucent = false;
                reader.beginObject();
                while (reader.hasNext()) {
                    String field = reader.nextName();
                    switch (field) {
                        case "sprite" -> sprite = IdentifierInterner.parse(reader.nextString());
                        case "force_translucent" -> forceTranslucent = reader.nextBoolean();
                        default -> reader.skipValue();
                    }
                }
                reader.endObject();
                require(sprite != null, "Texture material object missing 'sprite'");
                builder.addTexture(slot, new Material(sprite, forceTranslucent));
            } else {
                throw new UnsupportedFastModelException("Unsupported texture slot value");
            }
        }
        reader.endObject();
        return builder.build();
    }

    private static ItemTransforms parseTransforms(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "'display' must be an object");
        ItemTransform thirdLeft = ItemTransform.NO_TRANSFORM;
        ItemTransform thirdRight = ItemTransform.NO_TRANSFORM;
        ItemTransform firstLeft = ItemTransform.NO_TRANSFORM;
        ItemTransform firstRight = ItemTransform.NO_TRANSFORM;
        ItemTransform head = ItemTransform.NO_TRANSFORM;
        ItemTransform gui = ItemTransform.NO_TRANSFORM;
        ItemTransform ground = ItemTransform.NO_TRANSFORM;
        ItemTransform fixed = ItemTransform.NO_TRANSFORM;
        ItemTransform onShelf = ItemTransform.NO_TRANSFORM;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "thirdperson_lefthand" -> thirdLeft = parseTransform(reader);
                case "thirdperson_righthand" -> thirdRight = parseTransform(reader);
                case "firstperson_lefthand" -> firstLeft = parseTransform(reader);
                case "firstperson_righthand" -> firstRight = parseTransform(reader);
                case "head" -> head = parseTransform(reader);
                case "gui" -> gui = parseTransform(reader);
                case "ground" -> ground = parseTransform(reader);
                case "fixed" -> fixed = parseTransform(reader);
                case "on_shelf" -> onShelf = parseTransform(reader);
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (thirdLeft == ItemTransform.NO_TRANSFORM) thirdLeft = thirdRight;
        if (firstLeft == ItemTransform.NO_TRANSFORM) firstLeft = firstRight;
        return new ItemTransforms(thirdLeft, thirdRight, firstLeft, firstRight, head, gui, ground, fixed, onShelf);
    }

    private static ItemTransform parseTransform(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "Display transform must be an object");
        Vector3f rotation = new Vector3f();
        Vector3f translation = new Vector3f();
        Vector3f scale = new Vector3f(1f, 1f, 1f);

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            switch (field) {
                case "rotation" -> rotation = parseVec3(reader, "rotation");
                case "translation" -> translation = parseVec3(reader, "translation");
                case "scale" -> scale = parseVec3(reader, "scale");
                default -> reader.skipValue();
            }
        }
        reader.endObject();

        translation.mul(0.0625f);
        translation.set(clamp(translation.x, -5f, 5f), clamp(translation.y, -5f, 5f), clamp(translation.z, -5f, 5f));
        scale.set(clamp(scale.x, -4f, 4f), clamp(scale.y, -4f, 4f), clamp(scale.z, -4f, 4f));
        return new ItemTransform(rotation, translation, scale);
    }

    private static Vector3f parseVec3(JsonReader reader, String field) throws IOException {
        float[] values = parseFloatArray(reader, 3, field);
        return new Vector3f(values[0], values[1], values[2]);
    }

    private static float[] parseFloatArray(JsonReader reader, int expected, String field) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "Expected array for '" + field + "'");
        float[] values = new float[expected];
        int index = 0;
        reader.beginArray();
        while (reader.hasNext()) {
            if (index >= expected) throw new JsonParseException("Expected " + expected + " " + field + " values, found more");
            values[index++] = (float) reader.nextDouble();
        }
        reader.endArray();
        if (index != expected) throw new JsonParseException("Expected " + expected + " " + field + " values, found: " + index);
        return values;
    }

    private static void validateExtent(Vector3fc value, String field) {
        if (value.x() < -16f || value.y() < -16f || value.z() < -16f
                || value.x() > 32f || value.y() > 32f || value.z() > 32f) {
            throw new JsonParseException("'" + field + "' specifier exceeds the allowed boundaries: " + value);
        }
    }

    private static com.mojang.math.Quadrant quadrant(int degrees) {
        return switch (Math.floorMod(degrees, 360)) {
            case 0 -> com.mojang.math.Quadrant.R0;
            case 90 -> com.mojang.math.Quadrant.R90;
            case 180 -> com.mojang.math.Quadrant.R180;
            case 270 -> com.mojang.math.Quadrant.R270;
            default -> throw new JsonParseException("Invalid rotation " + degrees + " found, only 0/90/180/270 allowed");
        };
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new JsonParseException(message);
    }

    /** Marker used to choose a vanilla retry without treating a supported malformed file as valid. */
    public static final class UnsupportedFastModelException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public UnsupportedFastModelException(String message) { super(message); }
    }
}

