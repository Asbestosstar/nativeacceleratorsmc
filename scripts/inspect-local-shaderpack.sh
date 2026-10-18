#!/usr/bin/env bash
# Inspect a user-owned local shaderpack without copying or modifying it.
set -euo pipefail

PACK="${1:-$HOME/Library/Application Support/minecraft/shaderpacks/§r§lAstra§4§lLex§r§l_By_LexBoosT_§4§lV93.0§r§l.zip}"
if [[ ! -f "$PACK" && ! -d "$PACK" ]]; then
  printf 'BLOCKED: shaderpack not found: %s\n' "$PACK" >&2
  exit 2
fi

printf 'Shaderpack: %s\n' "$PACK"
if [[ -f "$PACK" ]]; then
  shasum -a 256 "$PACK"
  unzip -Z1 "$PACK" | wc -l | awk '{print "Archive entries: " $1}'
fi
printf 'This is metadata inspection only; no shader source is copied, compiled, or executed.\n'
