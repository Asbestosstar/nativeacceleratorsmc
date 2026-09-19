package com.asbestosstar.nativeaccelerator.renderer.metal;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persistent Native Accelerator graphics-backend preference.
 *
 * <p>Metal cannot be represented by Minecraft 26.3's three-value PreferredGraphicsApi enum, so the
 * fourth value is persisted independently. For the three vanilla choices we continue to mirror the
 * corresponding vanilla option, keeping the game bootable if the mod is removed.</p>
 */
public final class GraphicsBackendPreference {
    private static final Path PREFERENCE_FILE = Path.of(
            System.getProperty("user.dir", "."), "etc", "nativeaccelerator-graphics-api.txt");

    /** null means no Native Accelerator override has ever been stored. */
    private static volatile GraphicsApiChoice selected = loadStoredChoice();
    private static volatile GraphicsApiChoice startupChoice = selected;

    private GraphicsBackendPreference() {
    }

    /**
     * Value shown in the Native Accelerator GUI option. If no custom preference exists, inherit the
     * vanilla setting and remember that as the startup baseline for restart-warning purposes.
     */
    public static synchronized GraphicsApiChoice initialChoice(PreferredGraphicsApi vanilla) {
        if (selected == null) {
            selected = fromVanilla(vanilla);
        }
        if (startupChoice == null) {
            startupChoice = selected;
        }
        return selected;
    }

    public static GraphicsApiChoice currentChoice(PreferredGraphicsApi vanilla) {
        GraphicsApiChoice choice = selected;
        return choice != null ? choice : fromVanilla(vanilla);
    }

    public static synchronized void select(GraphicsApiChoice choice) {
        selected = choice == null ? GraphicsApiChoice.DEFAULT : choice;
        saveStoredChoice(selected);
    }

    public static boolean changedSinceStartup(PreferredGraphicsApi vanilla) {
        GraphicsApiChoice now = currentChoice(vanilla);
        GraphicsApiChoice start = startupChoice;
        if (start == null) {
            start = fromVanilla(vanilla);
        }
        return now != start;
    }

    /**
     * Minecraft 26.3 performs a standalone Vulkan availability probe whenever its vanilla option is
     * DEFAULT, before it tries the backend list.  Metal is persisted by mirroring DEFAULT, so without
     * this guard an explicitly selected Metal client would still load/probe Vulkan during startup.
     */
    public static boolean shouldRunVanillaVulkanProbe() {
        GraphicsApiChoice forced = forcedPropertyChoice();
        if (forced != null) {
            return forced == GraphicsApiChoice.VULKAN;
        }
        GraphicsApiChoice choice = selected;
        if (choice == null) {
            return true; // No Native Accelerator preference yet: preserve vanilla startup exactly.
        }
        // Native Accelerator owns its persisted Automatic path. Do not perform Minecraft's separate
        // eager Vulkan probe; Vulkan will be touched only if/when the candidate list actually reaches it.
        return choice == GraphicsApiChoice.VULKAN;
    }

    /**
     * Backend list used from Minecraft's constructor. A launch/crash-recovery override to OpenGL or
     * Vulkan is respected: Metal only replaces the vanilla DEFAULT path. This means Minecraft can
     * still recover from a failed startup by forcing OpenGL on the following boot.
     */
    public static GpuBackend[] backendsToTry(PreferredGraphicsApi vanillaSelection) {
        // Our explicit JVM property is a deliberate user/developer override and therefore wins even
        // when options.txt currently contains OpenGL/Vulkan. This makes OCLP bring-up independent of
        // the vanilla GUI and of stale crash-recovery state. Without the property, a non-default
        // vanilla value remains authoritative so Minecraft can still recover by forcing OpenGL.
        GraphicsApiChoice forced = forcedPropertyChoice();
        GraphicsApiChoice choice = forced != null
                ? forced
                : (vanillaSelection == PreferredGraphicsApi.DEFAULT
                    ? currentChoice(vanillaSelection)
                    : fromVanilla(vanillaSelection));

        return switch (choice) {
            case METAL -> {
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
                // Auto prefers Metal when SDL says it is genuinely usable, then OpenGL. Vulkan is kept
                // as the final automatic fallback only; no Vulkan companion work happens unless Vulkan
                // is the backend that actually succeeds.
                if (MetalBackend.shouldOffer()) {
                    System.out.println("[Native Accelerator] Graphics backend preference=default; trying Metal, OpenGL, then Vulkan");
                    yield new GpuBackend[]{new MetalBackend(), new GlBackend(), new VulkanBackend()};
                }
                yield vanillaSelection.getBackendsToTry();
            }
        };
    }

    public static PreferredGraphicsApi vanillaMirror(GraphicsApiChoice choice) {
        return switch (choice) {
            case VULKAN -> PreferredGraphicsApi.VULKAN;
            case OPENGL -> PreferredGraphicsApi.OPENGL;
            // Metal deliberately mirrors DEFAULT in options.txt. If Native Accelerator is removed,
            // Minecraft will therefore return to its normal safe automatic backend selection.
            case METAL, DEFAULT -> PreferredGraphicsApi.DEFAULT;
        };
    }

    public static GraphicsApiChoice fromVanilla(PreferredGraphicsApi vanilla) {
        if (vanilla == PreferredGraphicsApi.VULKAN) return GraphicsApiChoice.VULKAN;
        if (vanilla == PreferredGraphicsApi.OPENGL) return GraphicsApiChoice.OPENGL;
        return GraphicsApiChoice.DEFAULT;
    }

    private static GraphicsApiChoice loadStoredChoice() {
        GraphicsApiChoice forced = forcedPropertyChoice();
        if (forced != null) {
            return forced;
        }
        if (!Files.isRegularFile(PREFERENCE_FILE)) {
            return null;
        }
        try {
            String text = Files.readString(PREFERENCE_FILE, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : GraphicsApiChoice.fromSerializedName(text);
        } catch (IOException error) {
            System.err.println("[Native Accelerator] Could not read " + PREFERENCE_FILE + ": " + error.getMessage());
            return null;
        }
    }


    private static GraphicsApiChoice forcedPropertyChoice() {
        String forced = System.getProperty("nativeaccelerator.graphicsApi");
        if (forced == null || forced.isBlank()) return null;
        return GraphicsApiChoice.fromSerializedName(forced);
    }

    private static void saveStoredChoice(GraphicsApiChoice choice) {
        try {
            Files.createDirectories(PREFERENCE_FILE.getParent());
            Path temp = PREFERENCE_FILE.resolveSibling(PREFERENCE_FILE.getFileName() + ".tmp");
            Files.writeString(temp, choice.getSerializedName() + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, PREFERENCE_FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temp, PREFERENCE_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            System.err.println("[Native Accelerator] Could not save " + PREFERENCE_FILE + ": " + error.getMessage());
        }
    }
}
