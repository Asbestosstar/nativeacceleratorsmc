#!/usr/bin/env bash
# Run an externally supplied OpenGL/RenderPearl GPU smoke command.
# The command must launch the real Minecraft-owned device path and emit its own evidence.
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

command_text=${NATIVEACCELERATOR_OPENGL_SMOKE_COMMAND:-}
if [[ -z "$command_text" ]]; then
    printf 'OPENGL: BLOCKED (set NATIVEACCELERATOR_OPENGL_SMOKE_COMMAND to a real RenderPearl/Minecraft offscreen smoke command)\n'
    (( require == 1 )) && exit 1
    exit 0
fi

tmp_output=$(mktemp "${TMPDIR:-/tmp}/nativeaccelerator-opengl.XXXXXX")
trap 'rm -f "$tmp_output"' EXIT
printf 'OPENGL: running supplied GPU smoke command\n'
if ! bash -lc "$command_text" >"$tmp_output" 2>&1; then
    cat "$tmp_output"
    printf 'OPENGL: FAIL (supplied command failed)\n' >&2
    exit 1
fi
cat "$tmp_output"
if ! grep -q '^NATIVEACCELERATOR_GPU_EVIDENCE: PASS$' "$tmp_output"; then
    printf 'OPENGL: BLOCKED (command completed without the required real-device evidence marker)\n'
    (( require == 1 )) && exit 1
    exit 0
fi
printf 'OPENGL: PASS (real-device evidence marker verified)\n'
