package com.asbestosstar.nativeaccelerator.renderer;

import com.asbestosstar.nativeaccelerator.platform.LoaderEnvironment;
import com.asbestosstar.nativeaccelerator.renderer.nativeapi.PanamaRendererNativeApi;
import com.asbestosstar.nativeaccelerator.renderer.nativeapi.RendererNativeApi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Client-renderer policy decided by runtime evidence, never by operating system.
 *
 * <p>Two questions are asked, and both are answered by what the machine actually reports:</p>
 * <ol>
 *   <li><b>Does this computer support Vulkan?</b> The renderer companion library opens a Vulkan loader
 *       and enumerates physical devices. A Mesa, vendor, or translation-layer build that was compiled
 *       without usable Vulkan support answers no even on an operating system where Vulkan is common,
 *       and a machine that does provide Vulkan answers yes even when that is unusual for its OS.</li>
 *   <li><b>Which backend is Minecraft itself running?</b> The game own {@code options.txt} is parsed
 *       for the configured graphics backend. Native Accelerator has no OpenGL surface path, so a
 *       non-Vulkan backend means the client renderer rewrite has nothing to attach to.</li>
 * </ol>
 *
 * <p>{@link Role#SERVER_ONLY} therefore means the evidence says the Vulkan client renderer gives no
 * benefit on this machine, not that the operating system is unsupported. A server-only host still
 * starts the client, still plays normally, and still uses the full native compute accelerator; the
 * renderer companion library also still builds and remains usable for headless/dedicated-server
 * workloads.</p>
 *
 * <p>This class owns the single lazily loaded {@link RendererNativeApi} instance so the probe is not
 * repeated; {@link NativeVulkanRenderer} reuses it.</p>
 */
public final class RendererPlatformPolicy {
    /** Explicit override: auto (evidence), client, or server. */
    public static final String ROLE_PROPERTY = "nativeaccelerator.renderer.platform.role";
    /** Explicit path to the Minecraft options.txt used for backend evidence. */
    public static final String OPTIONS_PATH_PROPERTY = "nativeaccelerator.renderer.minecraft.options";
    /** Explicit Minecraft game directory whose options.txt is used for backend evidence. */
    public static final String GAME_DIRECTORY_PROPERTY = "nativeaccelerator.renderer.minecraft.gamedir";

    public static final String OPTIONS_FILE_NAME = "options.txt";

    /** Evidence bits, mirroring the NAR_RENDERER_EVIDENCE_ values in the native header. */
    public static final int EVIDENCE_VULKAN_LOADER = 1;
    public static final int EVIDENCE_VULKAN_DEVICE = 1 << 1;
    public static final int EVIDENCE_MINECRAFT_OPTIONS = 1 << 2;
    public static final int EVIDENCE_MINECRAFT_VULKAN = 1 << 3;
    public static final int EVIDENCE_MINECRAFT_NON_VULKAN = 1 << 4;
    public static final int EVIDENCE_OVERRIDE = 1 << 5;

    public enum Role {
        /** The evidence supports the client Vulkan renderer on this machine. */
        CLIENT,
        /** The evidence says the client Vulkan renderer gives no benefit; client still runs normally. */
        SERVER_ONLY
    }

    private static final Object LOCK = new Object();
    private static volatile RendererNativeApi api;
    private static volatile Throwable libraryFailure;
    private static volatile boolean failureReported;

    private static volatile int appliedOverride = Integer.MIN_VALUE;
    private static volatile String appliedOptionsPath;

    private static volatile Path gameDirectory;
    private static volatile Path optionsPath;

    private RendererPlatformPolicy() {}

    /**
     * The single native renderer instance used for probing, lazily loaded. Returns empty when the
     * renderer companion library cannot be loaded for this machine, which is itself evidence that the
     * client Vulkan renderer cannot run here.
     */
    public static Optional<RendererNativeApi> nativeApi() {
        RendererNativeApi local = api;
        if (local != null) return Optional.of(local);
        synchronized (LOCK) {
            if (api == null && libraryFailure == null) {
                try {
                    api = PanamaRendererNativeApi.loadBundled();
                } catch (Throwable t) {
                    libraryFailure = t;
                }
            }
            return Optional.ofNullable(api);
        }
    }

    /** Why the renderer companion library could not be loaded, when that is the case. */
    public static Optional<String> libraryUnavailableReason() {
        nativeApi();
        Throwable failure = libraryFailure;
        if (failure == null) return Optional.empty();
        String message = failure.getMessage();
        return Optional.of(message == null || message.isBlank() ? failure.getClass().getSimpleName() : message);
    }

    /** Tell the policy where Minecraft keeps its files, so its options.txt is authoritative. */
    public static void setMinecraftGameDirectory(Path directory) {
        gameDirectory = directory;
        optionsPath = null;
        synchronized (LOCK) {
            appliedOptionsPath = null;
        }
    }

    /** Point the Minecraft backend evidence at a specific options.txt. */
    public static void setMinecraftOptionsPath(Path file) {
        optionsPath = file;
        synchronized (LOCK) {
            appliedOptionsPath = null;
        }
    }

    /** The options.txt currently used for backend evidence, when one was resolved. */
    public static Optional<Path> minecraftOptionsPath() {
        return Optional.ofNullable(resolveOptionsPath());
    }

    /** Resolved client-renderer role for this machine, from runtime evidence or an explicit override. */
    public static Role role() {
        RendererNativeApi nativeRenderer = configure();
        if (nativeRenderer == null) return Role.SERVER_ONLY;
        return nativeRenderer.rendererClientEligible() ? Role.CLIENT : Role.SERVER_ONLY;
    }

    /** True when the evidence supports selecting the client Vulkan renderer. */
    public static boolean clientEligible() {
        return role() == Role.CLIENT;
    }

    /** Evidence bits behind the current role, or 0 when the renderer library is unavailable. */
    public static int evidence() {
        RendererNativeApi nativeRenderer = configure();
        return nativeRenderer == null ? 0 : nativeRenderer.rendererPlatformEvidence();
    }

    /** Human-readable evidence summary, for example: vulkan=device-ready minecraft=vulkan. */
    public static String describe() {
        RendererNativeApi nativeRenderer = configure();
        if (nativeRenderer == null) {
            return "renderer-library-unavailable (" + libraryUnavailableReason().orElse("unknown reason") + ")";
        }
        return nativeRenderer.rendererPlatformName();
    }

    /** True when the evidence includes a usable Vulkan device on this machine. */
    public static boolean vulkanDeviceDetected() {
        return (evidence() & EVIDENCE_VULKAN_DEVICE) != 0;
    }

    /** True when the Minecraft options.txt was located and read. */
    public static boolean minecraftOptionsDetected() {
        return (evidence() & EVIDENCE_MINECRAFT_OPTIONS) != 0;
    }

    /** True when an explicit role override is in force. */
    public static boolean overridden() {
        return (evidence() & EVIDENCE_OVERRIDE) != 0;
    }

    private static RendererNativeApi configure() {
        RendererNativeApi nativeRenderer = nativeApi().orElse(null);
        if (nativeRenderer == null) {
            if (!failureReported) {
                failureReported = true;
                System.out.println("[Native Accelerator] Renderer companion library is unavailable on this machine ("
                        + libraryUnavailableReason().orElse("unknown reason")
                        + "); the default renderer and the compute accelerator remain active.");
            }
            return null;
        }
        synchronized (LOCK) {
            int override = parseRole(System.getProperty(ROLE_PROPERTY));
            if (override != appliedOverride) {
                nativeRenderer.setRendererRoleOverride(override);
                appliedOverride = override;
            }
            Path resolved = resolveOptionsPath();
            String resolvedText = resolved == null ? null : resolved.toString();
            if (!Objects.equals(resolvedText, appliedOptionsPath)) {
                nativeRenderer.setMinecraftOptionsPath(resolvedText);
                appliedOptionsPath = resolvedText;
            }
        }
        return nativeRenderer;
    }

    private static int parseRole(String value) {
        if (value == null) return RendererNativeApi.ROLE_AUTO;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "client", "on", "client-eligible", "force-client" -> RendererNativeApi.ROLE_CLIENT;
            case "server", "off", "server-only", "force-server" -> RendererNativeApi.ROLE_SERVER_ONLY;
            default -> RendererNativeApi.ROLE_AUTO;
        };
    }

    /*
     * Resolve the options.txt to hand to the native policy. Explicit configuration is always honoured,
     * even when the file is missing, because the caller knows better. Discovered candidates are only
     * passed on when they really exist, so a wrong guess never shadows the native discovery order.
     */
    private static Path resolveOptionsPath() {
        Path explicit = optionsPath;
        if (explicit != null) return explicit;

        Path configured = firstExisting(
                pathProperty(OPTIONS_PATH_PROPERTY),
                gameDirectoryFromProperty());
        if (configured != null) return configured;

        Path directory = gameDirectory;
        if (directory != null) {
            Path candidate = directory.resolve(OPTIONS_FILE_NAME);
            if (Files.isRegularFile(candidate)) return candidate;
        }

        Path loaderDirectory = loaderGameDirectory();
        if (loaderDirectory != null) {
            Path candidate = loaderDirectory.resolve(OPTIONS_FILE_NAME);
            if (Files.isRegularFile(candidate)) return candidate;
        }

        return firstExisting(
                Path.of(System.getProperty("user.dir", "."), OPTIONS_FILE_NAME),
                Path.of(System.getProperty("user.dir", "."), "run", OPTIONS_FILE_NAME));
    }

    private static Path gameDirectoryFromProperty() {
        String value = System.getProperty(GAME_DIRECTORY_PROPERTY);
        if (value == null || value.isBlank()) return null;
        return Path.of(value).resolve(OPTIONS_FILE_NAME);
    }

    private static Path pathProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) return null;
        return Path.of(value);
    }

    private static Path firstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Adopt the running mod loader game directory as the Minecraft backend evidence location. Called at
     * client initialisation, when the loader already knows where Minecraft keeps its files, so the real
     * options.txt is read instead of a guessed path. Safe to call when no loader is present.
     */
    public static void adoptLoaderGameDirectory() {
        Path directory = loaderGameDirectory();
        if (directory != null) setMinecraftGameDirectory(directory);
    }

    /**
     * Ask the running mod loader for the Minecraft game directory. Delegated to the one shared
     * loader-identity class so this policy carries no per-loader knowledge of its own.
     */
    private static Path loaderGameDirectory() {
        return LoaderEnvironment.gameDirectory();
    }
}
