#!/usr/bin/env bash
# Run the dependency-light universal renderer and Metal plain-main tests.
# These tests do not launch Minecraft or claim GPU execution.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LABEL=universal-renderer
# shellcheck source=run-harness-common.sh
source "$PROJECT_DIR/scripts/run-harness-common.sh"

prepare_harness_classpath

TESTS=(
  com.asbestosstar.nativeaccelerator.client.LegacyShadersFooterLayoutTest
  com.asbestosstar.nativeaccelerator.client.LegacyShadersLocaleTest
  com.asbestosstar.nativeaccelerator.renderer.universal.CapabilityPlannerTest
  com.asbestosstar.nativeaccelerator.renderer.VulkanBackendDiagnosticsTest
  com.asbestosstar.nativeaccelerator.renderer.universal.GlslFrontendTest
  com.asbestosstar.nativeaccelerator.renderer.universal.IslFrontendTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ShaderSourceSpecTest
  com.asbestosstar.nativeaccelerator.renderer.universal.GlslPreprocessorTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ShaderpackCatalogTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ShaderpackConventionalScannerTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ShaderpackSettingsModelTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ConventionalShaderpackSelectionTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ConventionalShaderpackGraphAdapterTest
  com.asbestosstar.nativeaccelerator.renderer.universal.shader.legacy.ShaderProgramGraphTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ShaderpackConventionalVariantTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ShaderpackManifestTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ShaderpackPreparationTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ShaderpackPreparationRouterTest
  com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderpackSupportProfileTest
  com.asbestosstar.nativeaccelerator.renderer.universal.shader.ShaderCompilationIdentityTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ShaderpackRuntimeTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ShaderpackGenerationLifecycleTest
  com.asbestosstar.nativeaccelerator.renderer.universal.ShaderTechnologyStatusTest
  com.asbestosstar.nativeaccelerator.renderer.universal.TriangleMaterialTest
  com.asbestosstar.nativeaccelerator.renderer.universal.UrdShaderValidationTest
  com.asbestosstar.nativeaccelerator.renderer.universal.UrdPixelValidationTest
  com.asbestosstar.nativeaccelerator.renderer.universal.UrdBackendRegistryTest
  com.asbestosstar.nativeaccelerator.renderer.universal.UrdRenderGraphTest
  com.asbestosstar.nativeaccelerator.renderer.universal.UrdResourceLifetimeTest
  com.asbestosstar.nativeaccelerator.renderer.metal.MacMetalCapabilitiesTest
  com.asbestosstar.nativeaccelerator.renderer.metal.MetalLoggingTest
  com.asbestosstar.nativeaccelerator.renderer.metal.MetalCoordinatePolicyTest
  com.asbestosstar.nativeaccelerator.renderer.metal.MetalIndirectCommandDecoderTest
  com.asbestosstar.nativeaccelerator.renderer.metal.MetalQuadTriangulationPureTest
  com.asbestosstar.nativeaccelerator.renderer.metal.MetalUniformPackingTest
  com.asbestosstar.nativeaccelerator.renderer.metal.MetalUploadBufferPoolTest
)

for test_class in "${TESTS[@]}"; do
    run_harness_main "$test_class"
done

printf '[%s] RESULT: PASS (%d plain-main tests; no GPU claim)\n' "$LABEL" "${#TESTS[@]}"
