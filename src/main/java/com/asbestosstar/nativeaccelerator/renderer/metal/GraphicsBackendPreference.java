package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Native Accelerator graphics-backend preference.
 *
 * <p>Minecraft 26.3's {@link PreferredGraphicsApi} enum only contains Default/OpenGL/Vulkan, but its
 * persisted option is just the string-valued {@code preferredGraphicsBackend} entry in {@code options.txt}.
 * Native Accelerator extends the option's codec so that {@code "metal"} round-trips through that same
 * Minecraft option file. Internally Metal is mirrored to {@code PreferredGraphicsApi.DEFAULT} only where
 * Mojang's three-value Java enum is unavoidable; {@link #selected} remains the authoritative four-value
 * choice used by backend startup.</p>
 */
public final class GraphicsBackendPreference {
    private static final String OPTIONS_KEY = "preferredGraphicsBackend";
    private static final Path GAME_DIRECTORY = detectGameDirectory();
    private static final Path OPTIONS_FILE = GAME_DIRECTORY.resolve("options.txt");
    private static final Path LEGACY_PREFERENCE_FILE = GAME_DIRECTORY.resolve("etc/nativeaccelerator-graphics-api.txt");
    private static final Path LEGACY_CWD_PREFERENCE_FILE = Path.of(
            System.getProperty("user.dir", "."), "etc", "nativeaccelerator-graphics-api.txt").toAbsolutePath();
    private static final Path METAL_PENDING_FILE = GAME_DIRECTORY.resolve(
            ".nativeaccelerator/renderer/metal-startup.pending");

    /** null means options.txt had no renderer preference Native Accelerator could recognize. */
    private static volatile GraphicsApiChoice selected = loadStoredChoice();
    private static volatile GraphicsApiChoice startupChoice = selected;
    private static volatile boolean metalAttemptThisRun;
    private static volatile boolean metalBackendCreatedThisRun;

    private GraphicsBackendPreference() {}

    /**
     * Decode Minecraft's persisted graphics option. This is called by the codec installed on
     * PreferredGraphicsApi.CODEC, so options.txt may contain the fourth value "metal" without making
     * Mojang's enum itself unsafe to extend at runtime.
     */
    public static synchronized PreferredGraphicsApi decodeMinecraftOption(String serialized) {
        GraphicsApiChoice choice = parseChoice(serialized);
        if (choice == null) {
            System.err.println("[Native Accelerator] Unknown preferredGraphicsBackend='" + serialized
                    + "'; using default");
            choice = GraphicsApiChoice.DEFAULT;
        }
        selected = choice;
        if (startupChoice == null) startupChoice = choice;
        return vanillaMirror(choice);
    }

    /** Encode the authoritative four-value choice back into Minecraft's normal options.txt entry. */
    public static synchronized String encodeMinecraftOption(PreferredGraphicsApi vanillaValue) {
        GraphicsApiChoice choice = selected;
        if (choice == null) choice = fromVanilla(vanillaValue);
        return choice.getSerializedName();
    }

    public static synchronized GraphicsApiChoice initialChoice(PreferredGraphicsApi vanilla) {
        GraphicsApiChoice forced = forcedPropertyChoice();
        if (forced != null) {
            if (startupChoice == null) startupChoice = forced;
            return forced;
        }
        if (selected == null) selected = fromVanilla(vanilla);
        if (startupChoice == null) startupChoice = selected;
        return selected;
    }

    public static GraphicsApiChoice currentChoice(PreferredGraphicsApi vanilla) {
        GraphicsApiChoice forced = forcedPropertyChoice();
        if (forced != null) return forced;
        GraphicsApiChoice choice = selected;
        return choice != null ? choice : fromVanilla(vanilla);
    }

    public static synchronized void select(GraphicsApiChoice choice) {
        selected = choice == null ? GraphicsApiChoice.DEFAULT : choice;
        startupChoice = startupChoice == null ? selected : startupChoice;
        // A deliberate user selection is an explicit retry after any previous Metal startup failure.
        clearMetalPendingMarker();
        // The GUI calls Options.save() immediately afterwards; write here as well so options.txt is
        // authoritative even if a caller changes the renderer outside the normal video-options path.
        saveStoredChoice(selected);
        NativeAcceleratorConfig.setString("renderer.backend", selected.getSerializedName());
        System.out.println("[Native Accelerator] Saved preferredGraphicsBackend=\""
                + selected.getSerializedName() + "\" in " + OPTIONS_FILE);
    }

    public static boolean changedSinceStartup(PreferredGraphicsApi vanilla) {
        GraphicsApiChoice now = currentChoice(vanilla);
        GraphicsApiChoice start = startupChoice;
        if (start == null) start = fromVanilla(vanilla);
        return now != start;
    }

    /** Skip Minecraft's eager Vulkan probe whenever Native Accelerator owns a non-Vulkan startup path. */
    public static boolean shouldRunVanillaVulkanProbe() {
        GraphicsApiChoice forced = forcedPropertyChoice();
        if (forced != null) return forced == GraphicsApiChoice.VULKAN;
        GraphicsApiChoice choice = selected;
        if (choice == null) return true;
        return choice == GraphicsApiChoice.VULKAN;
    }

    /** Backend list used from Minecraft's constructor. */
    public static synchronized GpuBackend[] backendsToTry(PreferredGraphicsApi vanillaSelection) {
        GraphicsApiChoice forced = forcedPropertyChoice();
        GraphicsApiChoice choice;
        String source;
        if (forced != null) {
            choice = forced;
            source = "system-property";
        } else if (selected != null) {
            choice = selected;
            source = "options.txt";
        } else {
            choice = fromVanilla(vanillaSelection);
            source = "minecraft-option-object";
        }

        // If Metal crashed after device selection but before the first screen on the previous launch,
        // recover once to OpenGL. A forced -D...=metal deliberately bypasses recovery.
        if (choice == GraphicsApiChoice.METAL && forced == null && Files.isRegularFile(METAL_PENDING_FILE)) {
            System.err.println("[Native Accelerator] Previous Metal startup did not reach the first screen; "
                    + "recovering preferredGraphicsBackend to OpenGL. Re-select Metal to retry.");
            clearMetalPendingMarker();
            selected = GraphicsApiChoice.OPENGL;
            saveStoredChoice(selected);
            choice = GraphicsApiChoice.OPENGL;
            source = "metal-crash-recovery";
        }

        System.out.println("[Native Accelerator] Graphics preference source=" + source
                + " choice=" + choice.getSerializedName());

        return switch (choice) {
            case METAL -> {
                metalAttemptThisRun = true;
                metalBackendCreatedThisRun = false;
                markMetalPending();
                System.out.println("[Native Accelerator] Graphics backend preference=metal; trying Metal then OpenGL fallback");
                yield new GpuBackend[]{new MetalBackend(), new GlBackend()};
            }
            case OPENGL -> {
                System.out.println("[Native Accelerator] Graphics backend preference=opengl; Vulkan will not be probed");
                yield new GpuBackend[]{new GlBackend()};
            }
            case VULKAN -> {
                System.out.println("[Native Accelerator] Graphics backend preference=vulkan; trying Vulkan then OpenGL fallback");
                yield new GpuBackend[]{new VulkanBackend(), new GlBackend()};
            }
            case DEFAULT -> {
                if (MetalBackend.shouldOffer()) {
                    System.out.println("[Native Accelerator] Graphics backend preference=default; trying Metal, OpenGL, then Vulkan");
                    yield new GpuBackend[]{new MetalBackend(), new GlBackend(), new VulkanBackend()};
                }
                yield vanillaSelection.getBackendsToTry();
            }
        };
    }

    /** Called only after a backend successfully creates its GpuDevice. */
    public static synchronized void backendCreated(GpuBackend backend) {
        if (backend instanceof MetalBackend) {
            metalBackendCreatedThisRun = true;
            updateMetalPending("device-created");
            return;
        }
        if (metalAttemptThisRun && !metalBackendCreatedThisRun) {
            clearMetalPendingMarker();
            if (forcedPropertyChoice() == null) {
                selected = GraphicsApiChoice.OPENGL;
                saveStoredChoice(selected);
                System.err.println("[Native Accelerator] Metal device creation failed; OpenGL fallback succeeded. "
                        + "options.txt has been changed to preferredGraphicsBackend=\"opengl\"; "
                        + "re-select Metal to retry.");
            }
        }
    }

    /** Called when Minecraft reaches its first screen; a Metal startup that got this far is healthy. */
    public static synchronized void startupComplete() {
        if (metalBackendCreatedThisRun) clearMetalPendingMarker();
    }

    public static PreferredGraphicsApi vanillaMirror(GraphicsApiChoice choice) {
        return switch (choice) {
            case VULKAN -> PreferredGraphicsApi.VULKAN;
            case OPENGL -> PreferredGraphicsApi.OPENGL;
            case METAL, DEFAULT -> PreferredGraphicsApi.DEFAULT;
        };
    }

    public static GraphicsApiChoice fromVanilla(PreferredGraphicsApi vanilla) {
        if (vanilla == PreferredGraphicsApi.VULKAN) return GraphicsApiChoice.VULKAN;
        if (vanilla == PreferredGraphicsApi.OPENGL) return GraphicsApiChoice.OPENGL;
        return GraphicsApiChoice.DEFAULT;
    }

    private static GraphicsApiChoice loadStoredChoice() {
        String configured = NativeAcceleratorConfig.stringValue("renderer.backend", "inherit");
        if (!"inherit".equalsIgnoreCase(configured)) {
            GraphicsApiChoice configChoice = parseChoice(configured);
            if (configChoice != null) return configChoice;
        }
        GraphicsApiChoice optionsChoice = readOptionsChoice(OPTIONS_FILE);
        if (optionsChoice != null) return optionsChoice;

        // One-time compatibility with buildfix12 and older builds. options.txt remains the authority;
        // the legacy file is only imported when the Minecraft option is absent.
        GraphicsApiChoice legacy = readLegacyChoice(LEGACY_PREFERENCE_FILE);
        if (legacy == null && !LEGACY_PREFERENCE_FILE.toAbsolutePath().equals(LEGACY_CWD_PREFERENCE_FILE)) {
            legacy = readLegacyChoice(LEGACY_CWD_PREFERENCE_FILE);
        }
        if (legacy != null) {
            saveStoredChoice(legacy);
            System.out.println("[Native Accelerator] Migrated legacy renderer preference into " + OPTIONS_FILE);
        }
        return legacy;
    }

    private static GraphicsApiChoice readOptionsChoice(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                int colon = line.indexOf(':');
                if (colon <= 0 || !OPTIONS_KEY.equals(line.substring(0, colon))) continue;
                String raw = line.substring(colon + 1).trim();
                return parseChoice(unquoteJsonString(raw));
            }
        } catch (IOException error) {
            System.err.println("[Native Accelerator] Could not read " + path + ": " + error.getMessage());
        }
        return null;
    }

    private static GraphicsApiChoice readLegacyChoice(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try {
            return parseChoice(Files.readString(path, StandardCharsets.UTF_8).trim());
        } catch (IOException error) {
            return null;
        }
    }

    private static GraphicsApiChoice parseChoice(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        for (GraphicsApiChoice choice : GraphicsApiChoice.values()) {
            if (choice.getSerializedName().equalsIgnoreCase(normalized)) return choice;
        }
        return null;
    }

    private static String unquoteJsonString(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            // The four renderer names contain no escapes, but tolerate the standard escaped quote/backslash
            // forms so hand-edited options files remain unsurprising.
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return value;
    }

    private static GraphicsApiChoice forcedPropertyChoice() {
        String forced = System.getProperty("nativeaccelerator.graphicsApi");
        if (forced == null || forced.isBlank()) return null;
        GraphicsApiChoice choice = parseChoice(forced);
        return choice == null ? GraphicsApiChoice.DEFAULT : choice;
    }

    /** Atomically replace/add the preferredGraphicsBackend line while preserving every other option. */
    private static void saveStoredChoice(GraphicsApiChoice choice) {
        try {
            Path parent = OPTIONS_FILE.getParent();
            if (parent != null) Files.createDirectories(parent);
            List<String> lines = Files.isRegularFile(OPTIONS_FILE)
                    ? new ArrayList<>(Files.readAllLines(OPTIONS_FILE, StandardCharsets.UTF_8))
                    : new ArrayList<>();
            String replacement = OPTIONS_KEY + ":\"" + choice.getSerializedName() + "\"";
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int colon = line.indexOf(':');
                if (colon > 0 && OPTIONS_KEY.equals(line.substring(0, colon))) {
                    if (!replaced) {
                        lines.set(i, replacement);
                        replaced = true;
                    } else {
                        lines.remove(i--); // eliminate duplicate stale entries
                    }
                }
            }
            if (!replaced) lines.add(replacement);

            Path temp = OPTIONS_FILE.resolveSibling(OPTIONS_FILE.getFileName() + ".nativeaccelerator.tmp");
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp, OPTIONS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temp, OPTIONS_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            System.err.println("[Native Accelerator] Could not save " + OPTIONS_FILE + ": " + error.getMessage());
        }
    }

    private static void markMetalPending() {
        updateMetalPending("attempting");
    }

    private static void updateMetalPending(String state) {
        try {
            Files.createDirectories(METAL_PENDING_FILE.getParent());
            Files.writeString(METAL_PENDING_FILE,
                    "state=" + state + System.lineSeparator() + "time=" + Instant.now() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            System.err.println("[Native Accelerator] Could not write Metal startup marker "
                    + METAL_PENDING_FILE + ": " + error.getMessage());
        }
    }

    private static void clearMetalPendingMarker() {
        try {
            Files.deleteIfExists(METAL_PENDING_FILE);
        } catch (IOException error) {
            System.err.println("[Native Accelerator] Could not clear Metal startup marker "
                    + METAL_PENDING_FILE + ": " + error.getMessage());
        }
    }

    private static Path detectGameDirectory() {
        String target = System.getProperty("minecraft.applet.TargetDirectory");
        if (target != null && !target.isBlank()) return Path.of(target).toAbsolutePath().normalize();
        String gameDir = System.getProperty("minecraft.gameDir");
        if (gameDir != null && !gameDir.isBlank()) return Path.of(gameDir).toAbsolutePath().normalize();
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }
}

