package com.asbestosstar.nativeaccelerator.mixinconfig;

import com.asbestosstar.nativeaccelerator.platform.LoaderEnvironment;
import com.asbestosstar.nativeaccelerator.renderer.RendererPlatformPolicy;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Built-in, early-safe system gates for Native Accelerator mixins.
 *
 * <p>Native Accelerator ships a single mixin configuration for every loader. Loader-specific mixins are
 * not split into per-loader configurations; instead the companion
 * {@link org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin} decides at class-transform time
 * whether a mixin applies. This keeps the per-loader surface down to the thin entrypoints and means a
 * new loader never requires a new mixin config.</p>
 *
 * <p>This class supplies the default rules. It only reads signals that are safe this early in startup:
 * system properties and non-initializing class lookups. It must never reference Minecraft classes by
 * value, and it deliberately reuses {@link RendererPlatformPolicy#ROLE_PROPERTY} and
 * {@link LoaderEnvironment} instead of inventing parallel switches.</p>
 *
 * <h2>Naming convention (the contract for future mixins)</h2>
 * <p>A mixin's package decides which gate applies, so a new mixin is protected automatically:</p>
 * <ul>
 *   <li>{@code ...mixin.client.*} - applied only when a Minecraft client is present.</li>
 *   <li>{@code ...mixin.server.*} - applied only on a dedicated server (no client classes).</li>
 *   <li>{@code ...mixin.renderer.*} - GPU/Vulkan client-renderer hooks; honour
 *       {@code -Dnativeaccelerator.renderer.platform.role} and
 *       {@code -Dnativeaccelerator.mixins.renderer}.</li>
 *   <li>{@code ...mixin.loader.<loader>.*} - applied only when that mod loader is running, for example
 *       {@code ...mixin.loader.fabric.*} or {@code ...mixin.loader.neoforge.*}. This is the supported way
 *       to write a loader-specific mixin without a per-loader mixin config.</li>
 * </ul>
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li>{@code -Dnativeaccelerator.mixins=false} - disable the whole config (handled by the plugin).</li>
 *   <li>{@code -Dnativeaccelerator.mixins.renderer=false} - hard-disable renderer-group mixins.</li>
 *   <li>{@code -Dnativeaccelerator.mixins.environment=client|server} - force the environment
 *       assumption (mostly for tests and headless harnesses).</li>
 *   <li>{@code -Dnativeaccelerator.mixins.loaders=fabric,neoforge} - assume exactly these loaders are
 *       running instead of probing for loader classes (for tests, unusual launchers, and super-loaders).</li>
 *   <li>{@code -Dnativeaccelerator.renderer.platform.role=client|server} - already the project-wide
 *       renderer switch; reused here to force renderer mixins on or off.</li>
 * </ul>
 */
public final class SystemMixinGate {

    /** Mixins in this package require a real Minecraft client. */
    public static final String CLIENT_MIXIN_PACKAGE = "com.asbestosstar.nativeaccelerator.mixin.client.";
    /** Mixins in this package require a dedicated server (no client classes). */
    public static final String SERVER_MIXIN_PACKAGE = "com.asbestosstar.nativeaccelerator.mixin.server.";
    /** Mixins in this package hook the client GPU renderer. */
    public static final String RENDERER_MIXIN_PACKAGE = "com.asbestosstar.nativeaccelerator.mixin.renderer.";
    /**
     * Mixins in this package are loader-specific. The next path element names the loader, for example
     * {@code ...mixin.loader.fabric.ExampleMixin}. Such a mixin applies only when that loader is the one
     * running, which removes the need for a per-loader mixin configuration.
     */
    public static final String LOADER_MIXIN_PACKAGE = "com.asbestosstar.nativeaccelerator.mixin.loader.";

    /** Hard switch for the renderer mixin group. */
    public static final String RENDERER_MIXINS_PROPERTY = "nativeaccelerator.mixins.renderer";
    /** Force the client/server environment assumption instead of probing for the client class. */
    public static final String ENVIRONMENT_PROPERTY = "nativeaccelerator.mixins.environment";
    /**
     * Assume exactly these loaders are running, as a comma-separated list of loader ids
     * ({@code fabric}, {@code forge}, {@code neoforge}, {@code featurecreep}). Empty (the default) means
     * probe for the loader instead.
     */
    public static final String LOADERS_PROPERTY = "nativeaccelerator.mixins.loaders";

    /** Non-initializing anchor used to decide whether this is a client environment. */
    private static final String CLIENT_ANCHOR_CLASS = "net.minecraft.client.Minecraft";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private SystemMixinGate() {}

    /**
     * Register the built-in rules. Idempotent, so the plugin can call it from {@code onLoad} even when
     * Mixin loads several configs or reloads the same one.
     */
    public static void installDefaults() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        NativeAcceleratorMixinConfigPlugin.registerRule(SystemMixinGate::environmentRule);
        NativeAcceleratorMixinConfigPlugin.registerRule(SystemMixinGate::rendererRule);
        NativeAcceleratorMixinConfigPlugin.registerRule(SystemMixinGate::loaderRule);
    }

    /**
     * Skip client-only mixins on a dedicated server, and server-only mixins on a client. Any other
     * mixin (and an uncertain environment) is left to the remaining rules.
     */
    static MixinDecision environmentRule(String targetClassName, String mixinClassName) {
        String environment = environment();
        if (environment == null) return MixinDecision.DEFAULT;

        if (startsWith(CLIENT_MIXIN_PACKAGE, mixinClassName)) {
            return "server".equals(environment) ? MixinDecision.SKIP : MixinDecision.DEFAULT;
        }
        if (startsWith(SERVER_MIXIN_PACKAGE, mixinClassName)) {
            return "client".equals(environment) ? MixinDecision.SKIP : MixinDecision.DEFAULT;
        }
        return MixinDecision.DEFAULT;
    }

    /**
     * Renderer mixins are the ones that can take the game down on a machine whose Vulkan/Metal driver
     * aborts during device enumeration, so they are gated separately. An explicit client/server role
     * decides outright; in auto mode the decision is left to the mixin and the renderer policy rather
     * than being forced on.
     */
    static MixinDecision rendererRule(String targetClassName, String mixinClassName) {
        if (!startsWith(RENDERER_MIXIN_PACKAGE, mixinClassName)) return MixinDecision.DEFAULT;
        if (!booleanProperty(RENDERER_MIXINS_PROPERTY, true)) return MixinDecision.SKIP;

        return switch (rendererRole()) {
            case "client" -> MixinDecision.APPLY;
            case "server" -> MixinDecision.SKIP;
            default -> MixinDecision.DEFAULT;
        };
    }

    /**
     * Loader-specific mixins ({@code ...mixin.loader.<loader>.*}) apply only when that loader is actually
     * running. This is what lets the single universal mixin config carry loader-specific targets without
     * a per-loader config: a missing loader must skip its mixins, because the mixin would otherwise link
     * against a loader API that is not present.
     */
    static MixinDecision loaderRule(String targetClassName, String mixinClassName) {
        String loader = loaderOf(mixinClassName);
        if (loader == null) return MixinDecision.DEFAULT;
        return runningLoaders().contains(loader) ? MixinDecision.APPLY : MixinDecision.SKIP;
    }

    /**
     * The loader id encoded in a {@code ...mixin.loader.<loader>.*} mixin name, or {@code null} when the
     * mixin is not loader-scoped.
     */
    static String loaderOf(String mixinClassName) {
        if (!startsWith(LOADER_MIXIN_PACKAGE, mixinClassName)) return null;
        String rest = mixinClassName.substring(LOADER_MIXIN_PACKAGE.length());
        int dot = rest.indexOf('.');
        String loader = (dot >= 0 ? rest.substring(0, dot) : rest).trim().toLowerCase(Locale.ROOT);
        return loader.isEmpty() ? null : loader;
    }

    /**
     * The set of loader ids considered to be running. {@link #LOADERS_PROPERTY} wins when set; otherwise
     * the one loader detected by {@link LoaderEnvironment} is used. An undetected loader yields an empty
     * set, so loader-specific mixins are skipped rather than applied blindly.
     */
    static Set<String> runningLoaders() {
        String configured = System.getProperty(LOADERS_PROPERTY, "").trim();
        if (!configured.isEmpty()) {
            Set<String> ids = new LinkedHashSet<>();
            for (String item : configured.split(",")) {
                String id = item.trim().toLowerCase(Locale.ROOT);
                if (!id.isEmpty()) ids.add(id);
            }
            return ids;
        }

        String detected = LoaderEnvironment.current().id();
        return LoaderEnvironment.Loader.UNKNOWN.id().equals(detected)
                ? Set.of()
                : Set.of(detected);
    }

    /** Normalised value of the project-wide renderer role property. */
    static String rendererRole() {
        String value = System.getProperty(RendererPlatformPolicy.ROLE_PROPERTY, "").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "client", "on", "client-eligible", "force-client" -> "client";
            case "server", "off", "server-only", "force-server" -> "server";
            default -> "auto";
        };
    }

    /**
     * {@code "client"}, {@code "server"}, or {@code null} when it cannot be decided safely. This runs
     * during class transformation, so a lookup that fails for an unusual reason must not stop the game:
     * uncertainty defers to the other rules.
     */
    static String environment() {
        String forced = System.getProperty(ENVIRONMENT_PROPERTY, "").trim().toLowerCase(Locale.ROOT);
        if ("client".equals(forced) || "server".equals(forced)) return forced;

        ClassLoader loader = LoaderEnvironment.contextClassLoader();
        try {
            Class.forName(CLIENT_ANCHOR_CLASS, false, loader);
            return "client";
        } catch (ClassNotFoundException | LinkageError absent) {
            return "server";
        } catch (Throwable uncertain) {
            return null;
        }
    }

    private static boolean booleanProperty(String key, boolean fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) return fallback;
        return Boolean.parseBoolean(value.trim());
    }

    private static boolean startsWith(String prefix, String value) {
        return value != null && value.startsWith(prefix);
    }
}
