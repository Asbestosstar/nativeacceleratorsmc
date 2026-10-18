#!/usr/bin/env bash
# Run an externally supplied RenderPearl/Minecraft Vulkan GPU smoke command.
# This wrapper never treats the native Vulkan probe or an in-memory URD backend as GPU evidence.
set -euo pipefail

require=0
if [[ "${1:-}" == "--require" ]]; then
    require=1
    shift
fi
if [[ $# -ne 0 ]]; then
    printf 'usage: %s [--require]\n' "$0" >&2
    exit 2
fi

command_text=${NATIVEACCELERATOR_VULKAN_SMOKE_COMMAND:-}
if [[ -z "$command_text" ]]; then
    printf 'VULKAN: BLOCKED (set NATIVEACCELERATOR_VULKAN_SMOKE_COMMAND to a real RenderPearl/Minecraft offscreen smoke command)\n'
    (( require == 1 )) && exit 1
    exit 0
fi

tmp_output=$(mktemp "${TMPDIR:-/tmp}/nativeaccelerator-vulkan.XXXXXX")
trap 'rm -f "$tmp_output"' EXIT
printf 'VULKAN: running supplied GPU smoke command\n'
if ! bash -lc "$command_text" >"$tmp_output" 2>&1; then
    cat "$tmp_output"
    printf 'VULKAN: FAIL (supplied command failed)\n' >&2
    exit 1
fi
cat "$tmp_output"
if ! grep -q '^NATIVEACCELERATOR_GPU_EVIDENCE: PASS$' "$tmp_output"; then
    printf 'VULKAN: BLOCKED (command completed without the required real-device evidence marker)\n'
    (( require == 1 )) && exit 1
    exit 0
fi
printf 'VULKAN: PASS (real-device evidence marker verified)\n'
