package com.asbestosstar.nativeaccelerator.mixin.common;

import com.asbestosstar.nativeaccelerator.worldgen.FastMaterialRuleCompiler;
import com.asbestosstar.nativeaccelerator.worldgen.SurfaceDeepProfiler;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Compiles the vanilla surface rule tree into a flatter evaluator without changing leaf semantics. */
@Mixin(MaterialSystem.class)
public abstract class MaterialSystemFastRulesMixin {
    @Redirect(method = "buildSurface", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;compile(Lnet/minecraft/world/level/levelgen/material/MaterialRuleContext;)Lnet/minecraft/world/level/levelgen/material/rule/RuleEvaluator;"))
    private RuleEvaluator nativeaccelerator$compileRules(MaterialRule rule, MaterialRuleContext context) {
        boolean sample = SurfaceDeepProfiler.beginCompile();
        RuleEvaluator result = FastMaterialRuleCompiler.compile(rule, context);
        SurfaceDeepProfiler.endCompile(sample);
        return result;
    }
}
