package com.asbestosstar.nativeaccelerator.mixinconfig;

/**
 * Plain-main verification for the mixin system gate. There is no JUnit in the local repository, so the
 * assertions are explicit and a failure prints exactly which gate decision regressed.
 */
public final class SystemMixinGateTest {

    private static final String CLIENT_MIXIN = SystemMixinGate.CLIENT_MIXIN_PACKAGE + "ClientOnlyMixin";
    private static final String SERVER_MIXIN = SystemMixinGate.SERVER_MIXIN_PACKAGE + "ServerOnlyMixin";
    private static final String RENDERER_MIXIN = SystemMixinGate.RENDERER_MIXIN_PACKAGE + "VulkanRenderPassMixin";
    private static final String RENDER_PASS_TARGET = "com.mojang.renderpearl.frontend.FrontendRenderPass";
    private static final String MC_CLIENT = "net.minecraft.client.Minecraft";
    private static final String MC_SERVER = "net.minecraft.server.MinecraftServer";

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        NativeAcceleratorMixinConfigPlugin plugin = new NativeAcceleratorMixinConfigPlugin();
        plugin.onLoad("com.asbestosstar.nativeaccelerator.mixin");

        // --- environment rule, through the real plugin entry point ---------------------------
        System.setProperty(SystemMixinGate.ENVIRONMENT_PROPERTY, "server");
        expect("client mixin skipped on server", false, plugin.shouldApplyMixin(MC_CLIENT, CLIENT_MIXIN));
        expect("server mixin allowed on server", true, plugin.shouldApplyMixin(MC_SERVER, SERVER_MIXIN));

        System.setProperty(SystemMixinGate.ENVIRONMENT_PROPERTY, "client");
        expect("client mixin allowed on client", true, plugin.shouldApplyMixin(MC_CLIENT, CLIENT_MIXIN));
        expect("server mixin skipped on client", false, plugin.shouldApplyMixin(MC_SERVER, SERVER_MIXIN));

        // --- renderer rule -------------------------------------------------------------------
        System.setProperty(SystemMixinGate.RENDERER_MIXINS_PROPERTY, "true");
        System.setProperty("nativeaccelerator.renderer.platform.role", "server");
        expect("renderer mixin skipped when role=server", false,
                plugin.shouldApplyMixin(RENDER_PASS_TARGET, RENDERER_MIXIN));

        System.setProperty("nativeaccelerator.renderer.platform.role", "client");
        expect("renderer mixin applied when role=client", true,
                plugin.shouldApplyMixin(RENDER_PASS_TARGET, RENDERER_MIXIN));

        System.clearProperty("nativeaccelerator.renderer.platform.role");
        expect("renderer mixin deferred in auto role", true,
                plugin.shouldApplyMixin(RENDER_PASS_TARGET, RENDERER_MIXIN));

        System.setProperty(SystemMixinGate.RENDERER_MIXINS_PROPERTY, "false");
        System.setProperty("nativeaccelerator.renderer.platform.role", "client");
        expect("renderer hard-off beats role=client", false,
                plugin.shouldApplyMixin(RENDER_PASS_TARGET, RENDERER_MIXIN));

        // --- global disable ------------------------------------------------------------------
        System.setProperty(NativeAcceleratorMixinConfigPlugin.MIXINS_ENABLED_PROPERTY, "false");
        expect("mixins=false disables everything", false, plugin.shouldApplyMixin(MC_CLIENT, CLIENT_MIXIN));
        System.setProperty(NativeAcceleratorMixinConfigPlugin.MIXINS_ENABLED_PROPERTY, "true");

        // --- wildcard disable, then explicit override ----------------------------------------
        System.setProperty("nativeaccelerator.renderer.platform.role", "client");
        System.setProperty(NativeAcceleratorMixinConfigPlugin.DISABLED_MIXINS_PROPERTY, "*RenderPassMixin");
        plugin.reloadPropertyRules();
        expect("wildcard disable suppresses renderer mixin", false,
                plugin.shouldApplyMixin(RENDER_PASS_TARGET, RENDERER_MIXIN));

        NativeAcceleratorMixinConfigPlugin.enableMixin(RENDERER_MIXIN);
        expect("explicit enable beats wildcard disable", true,
                plugin.shouldApplyMixin(RENDER_PASS_TARGET, RENDERER_MIXIN));
        NativeAcceleratorMixinConfigPlugin.clearMixinDecision(RENDERER_MIXIN);

        // --- a rule that throws must not break the pipeline ----------------------------------
        // Reset state left over from the earlier blocks, or they short-circuit before the rules run:
        // the wildcard disable pattern, and the renderer hard-off switch.
        System.clearProperty(NativeAcceleratorMixinConfigPlugin.DISABLED_MIXINS_PROPERTY);
        System.setProperty(SystemMixinGate.RENDERER_MIXINS_PROPERTY, "true");
        plugin.reloadPropertyRules();
        NativeAcceleratorMixinConfigPlugin.registerRule((target, mixin) -> {
            throw new IllegalStateException("intentional test failure");
        });
        System.setProperty("nativeaccelerator.renderer.platform.role", "client");
        expect("broken rule is skipped, later rules still decide", true,
                plugin.shouldApplyMixin(RENDER_PASS_TARGET, RENDERER_MIXIN));

        // --- loader gate: loader-specific mixins without a per-loader config ------------------
        String FABRIC_MIXIN = SystemMixinGate.LOADER_MIXIN_PACKAGE + "fabric.ExampleFabricMixin";
        String NEOFORGE_MIXIN = SystemMixinGate.LOADER_MIXIN_PACKAGE + "neoforge.ExampleNeoForgeMixin";
        expect("loader id parsed from mixin name", "fabric", SystemMixinGate.loaderOf(FABRIC_MIXIN));
        expect("loader id parsed from neoforge mixin name", "neoforge", SystemMixinGate.loaderOf(NEOFORGE_MIXIN));
        expect("non-loader mixin has no loader id", null, SystemMixinGate.loaderOf(CLIENT_MIXIN));

        System.setProperty(SystemMixinGate.LOADERS_PROPERTY, "fabric");
        expect("fabric assumed -> fabric mixin applied", true,
                plugin.shouldApplyMixin(MC_CLIENT, FABRIC_MIXIN));
        expect("fabric assumed -> neoforge mixin skipped", false,
                plugin.shouldApplyMixin(MC_CLIENT, NEOFORGE_MIXIN));

        System.setProperty(SystemMixinGate.LOADERS_PROPERTY, "neoforge,forge");
        expect("neoforge-or-forge assumed -> neoforge mixin applied", true,
                plugin.shouldApplyMixin(MC_CLIENT, NEOFORGE_MIXIN));
        expect("neoforge-or-forge assumed -> fabric mixin skipped", false,
                plugin.shouldApplyMixin(MC_CLIENT, FABRIC_MIXIN));

        System.setProperty(SystemMixinGate.LOADERS_PROPERTY, "FABRIC , FeatureCreep");
        expect("loader ids are case and space normalised", 2, SystemMixinGate.runningLoaders().size());
        expect("normalised fabric loader applies", true,
                plugin.shouldApplyMixin(MC_CLIENT, FABRIC_MIXIN));

        System.clearProperty(SystemMixinGate.LOADERS_PROPERTY);
        expect("no loader assumed -> loader mixin skipped not applied", false,
                plugin.shouldApplyMixin(MC_CLIENT, FABRIC_MIXIN));

        // --- worldgen hot-path gates ----------------------------------------------------------
        String FAST_FILL = "com.asbestosstar.nativeaccelerator.mixin.common.NoiseBasedChunkGeneratorFastFillMixin";
        String FAST_SURFACE = "com.asbestosstar.nativeaccelerator.mixin.common.MaterialSystemFastHeightMixin";
        String FAST_LIGHT = "com.asbestosstar.nativeaccelerator.mixin.common.ChunkSkyLightSourcesFastMixin";
        String DEEP_SURFACE = "com.asbestosstar.nativeaccelerator.mixin.common.MaterialSystemDeepProfilingMixin";

        System.setProperty("nativeaccelerator.worldgen.fastFill", "false");
        expect("fast fill mixin removed when switch is false", MixinDecision.SKIP,
                SystemMixinGate.worldgenRule("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", FAST_FILL));
        System.clearProperty("nativeaccelerator.worldgen.fastFill");
        expect("fast fill mixin defaults on", MixinDecision.DEFAULT,
                SystemMixinGate.worldgenRule("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", FAST_FILL));

        System.setProperty("nativeaccelerator.worldgen.fastSurface", "false");
        expect("fast surface mixin removed when switch is false", MixinDecision.SKIP,
                SystemMixinGate.worldgenRule("net.minecraft.world.level.levelgen.material.MaterialSystem", FAST_SURFACE));
        System.clearProperty("nativeaccelerator.worldgen.fastSurface");
        expect("fast surface mixin defaults off pending parity validation", MixinDecision.SKIP,
                SystemMixinGate.worldgenRule("net.minecraft.world.level.levelgen.material.MaterialSystem", FAST_SURFACE));
        System.setProperty("nativeaccelerator.worldgen.fastSurface", "true");
        expect("fast surface mixin can be enabled explicitly", MixinDecision.DEFAULT,
                SystemMixinGate.worldgenRule("net.minecraft.world.level.levelgen.material.MaterialSystem", FAST_SURFACE));
        System.clearProperty("nativeaccelerator.worldgen.fastSurface");

        System.setProperty("nativeaccelerator.worldgen.fastLighting", "false");
        expect("fast lighting mixin removed when switch is false", MixinDecision.SKIP,
                SystemMixinGate.worldgenRule("net.minecraft.world.level.lighting.ChunkSkyLightSources", FAST_LIGHT));
        System.clearProperty("nativeaccelerator.worldgen.fastLighting");

        System.clearProperty("nativeaccelerator.worldgen.deepProfile");
        expect("deep profiler mixin defaults off", MixinDecision.SKIP,
                SystemMixinGate.worldgenRule("net.minecraft.world.level.levelgen.material.MaterialSystem", DEEP_SURFACE));
        System.setProperty("nativeaccelerator.worldgen.deepProfile", "true");
        expect("deep profiler mixin enabled explicitly", MixinDecision.DEFAULT,
                SystemMixinGate.worldgenRule("net.minecraft.world.level.levelgen.material.MaterialSystem", DEEP_SURFACE));
        System.clearProperty("nativeaccelerator.worldgen.deepProfile");

        System.out.println();
        System.out.println("checks=" + checks + " failures=" + failures);
        if (failures > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    private static void expect(String what, boolean expected, boolean actual) {
        checks++;
        if (expected == actual) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void expect(String what, Object expected, Object actual) {
        checks++;
        if (java.util.Objects.equals(expected, actual)) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void expect(String what, int expected, int actual) {
        expect(what, Integer.valueOf(expected), Integer.valueOf(actual));
    }

    private SystemMixinGateTest() {}
}

