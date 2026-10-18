#!/usr/bin/env bash
# Run the narrow Native Accelerator fixture pack through the Minecraft-created Metal device.
# This is an opt-in real-GPU test. It does not claim arbitrary Iris/OptiFine compatibility.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MC_DIR=${MC_DIR:-"$HOME/Library/Application Support/minecraft"}
FIXTURE="$PROJECT_DIR/src/main/resources/nativeaccelerator/shaderpacks/fixture"
PACK_ROOT="$MC_DIR/shaderpacks/nativeaccelerator-fixture"
LOG=${SHADERPACK_SMOKE_LOG:-"$PROJECT_DIR/target/minecraft-shaderpack-smoke.log"}

if [[ ! -d "$FIXTURE" ]]; then
    echo "[shaderpack-smoke] BLOCKED: fixture is missing: $FIXTURE" >&2
    exit 2
fi
if [[ ! -d "$MC_DIR" ]]; then
    echo "[shaderpack-smoke] BLOCKED: Minecraft directory is missing: $MC_DIR" >&2
    exit 2
fi
mkdir -p "$MC_DIR/shaderpacks" "$PROJECT_DIR/target"
rm -rf "$PACK_ROOT"
cp -R "$FIXTURE" "$PACK_ROOT"

export MC_DIR
export SKIP_BUILD=${SKIP_BUILD:-0}
export SMOKE_TEST=1
export STARTUP_LOG="$LOG"
# The fixture marker is the readiness condition for this smoke. Waiting only for a window would
# terminate the client before the backend callback reaches shader preparation.
export STARTUP_READY_PATTERN='NATIVEACCELERATOR_SHADERPACK_FIXTURE_GPU_EVIDENCE: PASS|BLOCKED: shaderpack|SHADERPACK_OUTPUT_VALIDATED'
export STARTUP_TIMEOUT=${STARTUP_TIMEOUT:-300}
export STARTUP_MAX_SECONDS=${STARTUP_MAX_SECONDS:-}
export FULLSCREEN=${FULLSCREEN:-0}

metal_args="-Dnativeaccelerator.graphicsApi=metal -Dnativeaccelerator.urd.enabled=true -Dnativeaccelerator.shaderpack=nativeaccelerator-fixture -Dnativeaccelerator.urd.demo=off"
if [[ -n "${EXTRA_JVM_ARGS:-}" ]]; then
    metal_args="$metal_args $EXTRA_JVM_ARGS"
fi
export EXTRA_JVM_ARGS="$metal_args"

cleanup() {
    rm -rf "$PACK_ROOT"
}
trap cleanup EXIT

set +e
"$PROJECT_DIR/test-macos.sh"
rc=$?
set -e
if [[ $rc -ne 0 ]]; then
    echo "[shaderpack-smoke] BLOCKED/FAILED: see $LOG" >&2
    exit "$rc"
fi

if grep -Eq 'NATIVEACCELERATOR_SHADERPACK_FIXTURE_GPU_EVIDENCE: PASS' "$LOG"; then
    echo "[shaderpack-smoke] PASS: real fixture pipeline/readback evidence recorded"
    exit 0
fi
if grep -Eq 'BLOCKED: shaderpack|SHADERPACK_' "$LOG"; then
    echo "[shaderpack-smoke] BLOCKED: shaderpack evidence was not complete; see $LOG"
    exit 2
fi
echo "[shaderpack-smoke] BLOCKED: no shaderpack markers found; see $LOG" >&2
exit 2
