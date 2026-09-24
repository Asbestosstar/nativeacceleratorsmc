# Metal clean-install fix28

The fix27 launch proved that source overlays were producing a hybrid JAR: the fix27 top-level marker
and MSL generator were present, but the running `MetalDevice` still contained buildfix25's
one-frame/resource-cycling experiment and the resource reload still contained buildfix26's forced
vanilla-atlas policy.

Fix28 therefore treats class identity as part of renderer correctness.

`INSTALL_FIX28.sh` deletes and replaces the complete Metal source package, restores the optimized
atlas mixins, removes `AtlasCompatibilityPolicy`, and deletes `build`, `target`, and `out` before the
next build. `VERIFY_FIX28.sh` inspects the compiled class bytes, not merely the top-level build marker.

The active MSL layout generator remains enabled. Runtime must report all four identities:
main build, clean Metal core, active MSL generator, and optimized atlas.

