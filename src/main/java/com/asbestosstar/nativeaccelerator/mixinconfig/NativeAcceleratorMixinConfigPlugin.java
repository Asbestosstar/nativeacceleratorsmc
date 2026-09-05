package com.asbestosstar.nativeaccelerator.mixinconfig;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Companion plugin for nativeaccelerator.mixins.json.
 *
 * Responsibilities:
 *  - veto Native Accelerator mixins before they are applied;
 *  - allow early bootstrap code to register conditional mixin rules;
 *  - expose Sponge's preApply/postApply ClassNode callbacks for transformations which need ASM;
 *  - remain safe to load before Minecraft and the normal mod entrypoints are initialized.
 *
 * This plugin controls mixins in its companion Native Accelerator mixin config. It is not a global
 * mechanism for suppressing arbitrary mixins belonging to other mods/configs.
 */
public final class NativeAcceleratorMixinConfigPlugin implements IMixinConfigPlugin {
    /** Disable the entire Native Accelerator mixin config with -Dnativeaccelerator.mixins=false. */
    public static final String MIXINS_ENABLED_PROPERTY = "nativeaccelerator.mixins";

    /**
     * Comma-separated list of mixin names/patterns to suppress.
     * Example: -Dnativeaccelerator.mixins.disable=ExperimentalMixin,*DaxMixin
     */
    public static final String DISABLED_MIXINS_PROPERTY = "nativeaccelerator.mixins.disable";

    private static final CopyOnWriteArrayList<MixinRule> RULES = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<ClassNodeHook> CLASS_NODE_HOOKS = new CopyOnWriteArrayList<>();

    /* Explicit bootstrap decisions have priority over property patterns and registered rules. */
    private static final Map<String, Boolean> EXPLICIT_DECISIONS = new ConcurrentHashMap<>();

    private static volatile List<String> disabledPatterns = readDisabledPatterns();

    /**
     * Register a conditional mixin rule. This must be called before the relevant mixin is selected.
     * Rules should depend only on early-safe information such as system properties, OS, CPU family,
     * available classes, or loader presence, not on initialized Minecraft state.
     */
    public static void registerRule(MixinRule rule) {
        if (rule == null) throw new NullPointerException("rule");
        RULES.add(rule);
    }

    /** Register a ClassNode callback invoked from Sponge Mixin's preApply/postApply hooks. */
    public static void registerClassNodeHook(ClassNodeHook hook) {
        if (hook == null) throw new NullPointerException("hook");
        CLASS_NODE_HOOKS.add(hook);
    }

    /** Explicitly suppress a mixin. Accepts a fully-qualified name or a simple mixin class name. */
    public static void disableMixin(String mixinClassName) {
        EXPLICIT_DECISIONS.put(normalizeName(mixinClassName), Boolean.FALSE);
    }

    /** Explicitly allow a mixin, overriding the disable-pattern property for that exact name. */
    public static void enableMixin(String mixinClassName) {
        EXPLICIT_DECISIONS.put(normalizeName(mixinClassName), Boolean.TRUE);
    }

    /** Remove an explicit enable/disable decision and return the mixin to normal rule evaluation. */
    public static void clearMixinDecision(String mixinClassName) {
        EXPLICIT_DECISIONS.remove(normalizeName(mixinClassName));
    }

    /** Re-read -Dnativeaccelerator.mixins.disable. Useful for test harnesses and early bootstrap. */
    public static void reloadPropertyRules() {
        disabledPatterns = readDisabledPatterns();
    }

    @Override
    public void onLoad(String mixinPackage) {
        reloadPropertyRules();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!Boolean.parseBoolean(System.getProperty(MIXINS_ENABLED_PROPERTY, "true"))) {
            return false;
        }

        Boolean explicit = findExplicitDecision(mixinClassName);
        if (explicit != null) {
            return explicit;
        }

        for (String pattern : disabledPatterns) {
            if (matches(pattern, mixinClassName)) {
                return false;
            }
        }

        for (MixinRule rule : RULES) {
            MixinDecision decision;
            try {
                decision = rule.decide(targetClassName, mixinClassName);
            } catch (Throwable t) {
                // Config plugins run during class transformation. A broken optional rule should not
                // make the whole game unbootable; leave the decision to the remaining rules.
                System.err.println("[Native Accelerator] Mixin rule failed for " + mixinClassName
                        + " -> " + targetClassName + ": " + t);
                continue;
            }

            if (decision == MixinDecision.APPLY) return true;
            if (decision == MixinDecision.SKIP) return false;
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // Observation point intentionally retained. Do not remove targets just because another
        // config also targets them; multiple compatible mixins on one class are normal.
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {
        for (ClassNodeHook hook : CLASS_NODE_HOOKS) {
            hook.preApply(targetClassName, targetClass, mixinClassName, mixinInfo);
        }
    }

    @Override
    public void postApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {
        for (ClassNodeHook hook : CLASS_NODE_HOOKS) {
            hook.postApply(targetClassName, targetClass, mixinClassName, mixinInfo);
        }
    }

    private static Boolean findExplicitDecision(String mixinClassName) {
        String normalized = normalizeName(mixinClassName);

        Boolean exact = EXPLICIT_DECISIONS.get(normalized);
        if (exact != null) return exact;

        int dot = normalized.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < normalized.length()) {
            return EXPLICIT_DECISIONS.get(normalized.substring(dot + 1));
        }
        return null;
    }

    private static List<String> readDisabledPatterns() {
        String raw = System.getProperty(DISABLED_MIXINS_PROPERTY, "").trim();
        if (raw.isEmpty()) return Collections.emptyList();

        List<String> result = new ArrayList<>();
        for (String item : raw.split(",")) {
            String pattern = item.trim();
            if (!pattern.isEmpty()) result.add(pattern);
        }
        return List.copyOf(result);
    }

    private static String normalizeName(String value) {
        if (value == null) throw new NullPointerException("mixinClassName");
        return value.trim();
    }

    /** Simple '*' wildcard matcher; matching is case-sensitive because Java class names are. */
    private static boolean matches(String pattern, String className) {
        if (pattern.equals(className)) return true;

        // A simple name is allowed for convenience.
        if (pattern.indexOf('.') < 0 && pattern.indexOf('*') < 0) {
            int dot = className.lastIndexOf('.');
            return dot >= 0 && className.substring(dot + 1).equals(pattern);
        }

        int p = 0;
        int c = 0;
        int star = -1;
        int retry = -1;

        while (c < className.length()) {
            if (p < pattern.length() && pattern.charAt(p) == className.charAt(c)) {
                p++;
                c++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                retry = c;
            } else if (star >= 0) {
                p = star + 1;
                c = ++retry;
            } else {
                return false;
            }
        }

        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }
}
