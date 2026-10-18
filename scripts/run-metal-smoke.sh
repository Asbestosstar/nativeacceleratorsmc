#!/usr/bin/env bash
# Run an externally supplied macOS Metal/RenderPearl GPU smoke command.
# Pure Java Metal helper tests belong to run-universal-renderer-tests.sh; this is real-device evidence only.
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

if [[ "$(uname -s)" != "Darwin" ]]; then
    printf 'METAL: BLOCKED (Metal requires macOS)\n'
    (( require == 1 )) && exit 1
    exit 0
fi

command_text=${NATIVEACCELERATOR_METAL_SMOKE_COMMAND:-}
if [[ -z "$command_text" ]]; then
    printf 'METAL: BLOCKED (set NATIVEACCELERATOR_METAL_SMOKE_COMMAND to a real device smoke command)\n'
    (( require == 1 )) && exit 1
    exit 0
fi

tmp_output=$(mktemp "${TMPDIR:-/tmp}/nativeaccelerator-metal.XXXXXX")
trap 'rm -f "$tmp_output"' EXIT
printf 'METAL: running supplied GPU smoke command\n'
if ! bash -lc "$command_text" >"$tmp_output" 2>&1; then
    cat "$tmp_output"
    printf 'METAL: FAIL (supplied command failed)\n' >&2
    exit 1
fi
cat "$tmp_output"
if ! grep -q '^NATIVEACCELERATOR_GPU_EVIDENCE: PASS$' "$tmp_output"; then
    printf 'METAL: BLOCKED (command completed without the required real-device evidence marker)\n'
    (( require == 1 )) && exit 1
    exit 0
fi
printf 'METAL: PASS (real-device evidence marker verified)\n'
