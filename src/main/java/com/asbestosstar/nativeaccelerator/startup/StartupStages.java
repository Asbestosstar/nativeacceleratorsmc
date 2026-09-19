package com.asbestosstar.nativeaccelerator.startup;

/**
 * Canonical stage names for the startup-timing mixins. Stage names are stable dotted ids so reports
 * and baselines remain comparable across builds.
 */
public final class StartupStages {

    /* ---- whole-process entrypoints ------------------------------------------------ */
    public static final String CLIENT_MAIN = "client.main";
    public static final String SERVER_MAIN = "server.main";

    /* ---- common bootstrap --------------------------------------------------------- */
    public static final String BOOTSTRAP = "bootstrap";
    /** Whole {@code FireBlock.bootStrap()}, including its first access to {@code Blocks.FIRE}. */
    public static final String BOOTSTRAP_FIRE = "bootstrap.fire-block";
    /** Vanilla FireBlock flammability-map population, excluding first {@code Blocks} class access. */
    public static final String BOOTSTRAP_FIRE_RULES = "bootstrap.fire-block-rules";
    public static final String BOOTSTRAP_SELECTORS = "bootstrap.entity-selectors";
    public static final String BOOTSTRAP_DISPENSE = "bootstrap.dispense-behaviors";
    public static final String BOOTSTRAP_CAULDRON = "bootstrap.cauldron-interactions";
    public static final String BOOTSTRAP_REGISTRIES = "bootstrap.built-in-registries";
    public static final String BOOTSTRAP_CREATIVE_TABS = "bootstrap.creative-tabs";
    public static final String BOOTSTRAP_LOOT_CONTEXT = "bootstrap.loot-context-params";
    public static final String BOOTSTRAP_VALIDATE = "bootstrap.validate";

    /* ---- client ------------------------------------------------------------------- */
    public static final String CLIENT_MINECRAFT_INIT = "client.minecraft-init";
    public static final String CLIENT_GAME_LOOP = "client.game-loop";
    public static final String CLIENT_RESOURCE_RELOAD = "client.resource-reload";
    /** Aggregate greedy texture-atlas packing time across all client Stitcher instances. */
    public static final String CLIENT_ATLAS_STITCH = "client.atlas-stitch";
    public static final String CLIENT_LOADING_OVERLAY = "client.loading-overlay";
    public static final String CLIENT_LOADING = "client.loading";
    public static final String CLIENT_TITLE_SCREEN = "client.title-screen";
    public static final String CLIENT_INTEGRATED_SERVER = "client.integrated-server-init";

    /* ---- dedicated server --------------------------------------------------------- */
    public static final String SERVER_RUN_LOOP = "server.run-loop";
    public static final String SERVER_INIT = "server.init";
    /** Fresh-world global spawn search, excluding subsequent initial-chunk readiness. */
    public static final String SERVER_GLOBAL_SPAWN = "server.global-spawn";
    /** Waiting for the server's initial chunk set after the global spawn has been selected. */
    public static final String SERVER_INITIAL_CHUNKS = "server.initial-chunks";
    public static final String SERVER_READY = "server.ready";

    private StartupStages() {}
}
