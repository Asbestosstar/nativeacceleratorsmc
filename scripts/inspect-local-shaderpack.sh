#!/usr/bin/env bash
# Inspect a user-owned local shaderpack without copying or modifying it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/platform.sh
. "$SCRIPT_DIR/lib/platform.sh"
PACK="${1:-$(na_minecraft_home)/shaderpacks/§r§lAstra§4§lLex§r§l_By_LexBoosT_§4§lV93.0§r§l.zip}"
if [[ ! -f "$PACK" && ! -d "$PACK" ]]; then
  printf 'BLOCKED: shaderpack not found: %s\n' "$PACK" >&2
  exit 2
fi

printf 'Shaderpack: %s\n' "$PACK"
if [[ -f "$PACK" ]]; then
  digest=$(na_digest sha256 "$PACK") || { printf 'BLOCKED: no SHA-256 tool found\n' >&2; exit 2; }
  printf '%s  %s\n' "$digest" "$PACK"
  if command -v unzip >/dev/null 2>&1; then
    unzip -Z1 "$PACK" | wc -l | awk '{print "Archive entries: " $1}'
  else
    printf 'Archive entries: unavailable (unzip is not installed)\n'
  fi
fi
printf 'This is metadata inspection only; no shader source is copied, compiled, or executed.\n'
