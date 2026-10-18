#!/usr/bin/env bash
# Dispatch universal unit tests and explicitly selected GPU/native smoke tests.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
usage() {
    printf 'usage: %s [universal|opengl|vulkan|metal|native-vulkan|all]\n' "$0" >&2
}

selection=${1:-universal}
case "$selection" in
  universal)
    exec "$ROOT/scripts/run-universal-renderer-tests.sh"
    ;;
  opengl)
    exec "$ROOT/scripts/run-opengl-smoke.sh"
    ;;
  vulkan)
    exec "$ROOT/scripts/run-vulkan-smoke.sh"
    ;;
  metal)
    exec "$ROOT/scripts/run-metal-smoke.sh"
    ;;
  native-vulkan)
    exec "$ROOT/scripts/run-native-vulkan-smoke.sh"
    ;;
  all)
    "$ROOT/scripts/run-universal-renderer-tests.sh"
    if [[ "${NATIVEACCELERATOR_REQUIRE_GPU:-0}" == "1" ]]; then
        "$ROOT/scripts/run-opengl-smoke.sh" --require
        "$ROOT/scripts/run-vulkan-smoke.sh" --require
        "$ROOT/scripts/run-metal-smoke.sh" --require
        "$ROOT/scripts/run-native-vulkan-smoke.sh" --require-gpu
    else
        "$ROOT/scripts/run-opengl-smoke.sh"
        "$ROOT/scripts/run-vulkan-smoke.sh"
        "$ROOT/scripts/run-metal-smoke.sh"
        "$ROOT/scripts/run-native-vulkan-smoke.sh"
    fi
    if [[ "${NATIVEACCELERATOR_REQUIRE_GPU:-0}" == "1" ]]; then
        printf 'RENDERER: PASS (all requested unit and GPU/native smoke tests completed)\n'
    else
        printf 'RENDERER: COMPLETE (unit tests passed; unavailable GPU backends remain explicitly BLOCKED)\n'
    fi
    ;;
  *)
    usage
    exit 2
    ;;
esac
