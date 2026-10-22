#!/usr/bin/env bash
# Build and run the native Vulkan companion's non-GPU tests, then optionally probe a real loader.
# Scene/arena tests are not reported as GPU execution.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR=${NATIVEACCELERATOR_NATIVE_BUILD_DIR:-$ROOT/target/native-renderer-smoke}
require_gpu=0
if [[ "${1:-}" == "--require-gpu" ]]; then
    require_gpu=1
    shift
fi
if [[ $# -ne 0 ]]; then
    printf 'usage: %s [--require-gpu]\n' "$0" >&2
    exit 2
fi

if ! command -v cmake >/dev/null 2>&1 || ! command -v ctest >/dev/null 2>&1; then
    printf 'NATIVE-VULKAN: BLOCKED (cmake and ctest are required)\n'
    (( require_gpu == 1 )) && exit 1
    exit 0
fi

printf 'NATIVE-VULKAN: building companion tests\n'
cmake -S "$ROOT/native" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=RelWithDebInfo -DBUILD_TESTING=ON
cmake --build "$BUILD_DIR" --config RelWithDebInfo --parallel
(cd "$BUILD_DIR" && ctest -C RelWithDebInfo --output-on-failure)
printf 'NATIVE-VULKAN: PASS (CPU/ABI/scene tests; no GPU execution claimed)\n'

if [[ -n "${NATIVEACCELERATOR_NATIVE_VULKAN_PROBE_COMMAND:-}" ]]; then
    tmp_output=$(mktemp "${TMPDIR:-/tmp}/nativeaccelerator-native-vulkan.XXXXXX")
    trap 'rm -f "$tmp_output"' EXIT
    printf 'NATIVE-VULKAN: running supplied real-loader probe\n'
    if ! bash -lc "$NATIVEACCELERATOR_NATIVE_VULKAN_PROBE_COMMAND" >"$tmp_output" 2>&1; then
        cat "$tmp_output"
        printf 'NATIVE-VULKAN: FAIL (supplied probe failed)\n' >&2
        exit 1
    fi
    cat "$tmp_output"
    if ! grep -q '^NATIVEACCELERATOR_GPU_EVIDENCE: PASS$' "$tmp_output"; then
        printf 'NATIVE-VULKAN: BLOCKED (probe completed without the required real-device evidence marker)\n'
        (( require_gpu == 1 )) && exit 1
        exit 0
    fi
    printf 'NATIVE-VULKAN: PASS (real-device evidence marker verified)\n'
elif (( require_gpu == 1 )); then
    printf 'NATIVE-VULKAN: BLOCKED (set NATIVEACCELERATOR_NATIVE_VULKAN_PROBE_COMMAND for real GPU evidence)\n'
    exit 1
else
    printf 'NATIVE-VULKAN: BLOCKED (no real GPU probe supplied)\n'
fi
