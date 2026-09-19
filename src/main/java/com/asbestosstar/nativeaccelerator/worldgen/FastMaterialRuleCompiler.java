package com.asbestosstar.nativeaccelerator.worldgen;

import com.asbestosstar.nativeaccelerator.config.NativeAcceleratorConfig;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.condition.ConditionEvaluator;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.rule.BlockRule;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Semantics-preserving compiler for the compositional surface-rule nodes used heavily by vanilla.
 * It flattens nested sequences and consecutive condition chains while delegating every leaf/condition
 * implementation to Minecraft. Unsupported rule types therefore retain vanilla behavior automatically.
 */
public final class FastMaterialRuleCompiler {
    private static final boolean ENABLED = NativeAcceleratorConfig.booleanValue("worldgen.fastSurfaceRules", true);
    private static final int MAX_DEPTH = 128;

    private FastMaterialRuleCompiler() {}

    public static boolean enabled() { return ENABLED; }

    public static RuleEvaluator compile(MaterialRule rule, MaterialRuleContext context) {
        if (!ENABLED || rule == null) return rule.compile(context);
        try {
            return compileNode(unwrap(rule), context, new CompileSession(), 0);
        } catch (Throwable ignored) {
            // Optimization failure must never make world generation fail; compile the exact vanilla tree.
            return rule.compile(context);
        }
    }

    private static RuleEvaluator compileNode(MaterialRule rule, MaterialRuleContext context,
                                             CompileSession session, int depth) {
        if (depth > MAX_DEPTH) return rule.compile(context);
        rule = unwrap(rule);
        if (rule instanceof BlockRule block) return block;
        if (rule instanceof SequenceRule sequence) return compileSequence(sequence, context, session, depth + 1);
        if (rule instanceof ConditionRule condition) return compileConditionChain(condition, context, session, depth + 1);
        return rule.compile(context);
    }

    private static RuleEvaluator compileSequence(SequenceRule root, MaterialRuleContext context,
                                                 CompileSession session, int depth) {
        ArrayList<MaterialRule> flat = new ArrayList<>();
        flattenSequence(root, flat, depth);
        if (flat.size() == 1) return compileNode(flat.getFirst(), context, session, depth + 1);
        RuleEvaluator[] evaluators = new RuleEvaluator[flat.size()];
        for (int i = 0; i < evaluators.length; ++i) {
            evaluators[i] = compileNode(flat.get(i), context, session, depth + 1);
        }
        return (x, y, z) -> {
            for (RuleEvaluator evaluator : evaluators) {
                BlockState state = evaluator.tryApply(x, y, z);
                if (state != null) return state;
            }
            return null;
        };
    }

    private static void flattenSequence(MaterialRule rule, List<MaterialRule> out, int depth) {
        if (depth > MAX_DEPTH) { out.add(rule); return; }
        MaterialRule unwrapped = unwrap(rule);
        if (unwrapped instanceof SequenceRule sequence) {
            for (MaterialRule child : sequence.sequence()) flattenSequence(child, out, depth + 1);
        } else {
            out.add(unwrapped);
        }
    }

    private static RuleEvaluator compileConditionChain(ConditionRule first, MaterialRuleContext context,
                                                       CompileSession session, int depth) {
        ArrayList<ConditionEvaluator> conditions = new ArrayList<>(4);
        MaterialRule cursor = first;
        int d = depth;
        while (d++ <= MAX_DEPTH) {
            cursor = unwrap(cursor);
            if (!(cursor instanceof ConditionRule condition)) break;
            // Preserve vanilla compile order exactly: condition first, then child. Reusing the compiled
            // evaluator for the *same condition object* is safe because MaterialCondition is a pure query
            // over this MaterialRuleContext; it also lets Minecraft's LazyXZ/LazyY evaluators share caches
            // when a condition object is referenced from more than one branch.
            conditions.add(session.condition(condition.ifTrue(), context));
            cursor = condition.thenRun();
        }
        final RuleEvaluator terminal = compileNode(unwrap(cursor), context, session, d);
        final ConditionEvaluator[] tests = conditions.toArray(ConditionEvaluator[]::new);
        if (terminal instanceof BlockRule block) {
            BlockState state = block.resultState();
            return (x, y, z) -> {
                for (ConditionEvaluator test : tests) if (!test.test()) return null;
                return state;
            };
        }
        return (x, y, z) -> {
            for (ConditionEvaluator test : tests) if (!test.test()) return null;
            return terminal.tryApply(x, y, z);
        };
    }

    private static MaterialRule unwrap(MaterialRule rule) {
        int depth = 0;
        while (rule instanceof MaterialRule.HolderHolder holder && depth++ < MAX_DEPTH) {
            rule = holder.holder().value();
        }
        return rule;
    }

    private static final class CompileSession {
        private final IdentityHashMap<MaterialCondition, ConditionEvaluator> conditions = new IdentityHashMap<>();

        ConditionEvaluator condition(MaterialCondition condition, MaterialRuleContext context) {
            // Preserve arbitrary mod-condition compile semantics. Only Mojang's built-in condition classes are
            // treated as pure/cacheable here; a third-party condition may intentionally return distinct stateful
            // evaluators from repeated compile() calls.
            Package pkg = condition.getClass().getPackage();
            boolean builtIn = pkg != null
                    && "net.minecraft.world.level.levelgen.material.condition".equals(pkg.getName());
            if (!builtIn) return condition.compile(context);

            ConditionEvaluator existing = conditions.get(condition);
            if (existing != null) return existing;
            // Do not use computeIfAbsent: a condition is allowed to throw, and the outer compiler fallback must
            // see the original exception rather than Map callback wrapping/recursion behavior.
            ConditionEvaluator compiled = condition.compile(context);
            conditions.put(condition, compiled);
            return compiled;
        }
    }
}
