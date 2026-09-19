package com.asbestosstar.nativeaccelerator.mixinconfig;

/**
 * Tri-state result for a Native Accelerator mixin rule.
 * DEFAULT means that the rule has no opinion and evaluation should continue.
 */
public enum MixinDecision {
    DEFAULT,
    APPLY,
    SKIP
}
