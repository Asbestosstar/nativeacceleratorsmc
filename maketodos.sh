#!/bin/sh
# Build a single, shareable project-context file for ChatGPT/code-review sessions.
# Includes repository instructions, documentation, and source files; excludes generated output.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUTPUT=${1:-"$ROOT/chatgpt-context.txt"}

case "$OUTPUT" in
    /*) ;;
    *) OUTPUT="$ROOT/$OUTPUT" ;;
esac

TMP_OUTPUT="$OUTPUT.tmp.$$"
trap 'rm -f "$TMP_OUTPUT"' EXIT HUP INT TERM

mkdir -p "$(dirname -- "$OUTPUT")"

{
    printf '%s\n' '# Project context export'
    printf '%s\n' "# Generated: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf '%s\n\n' "# Repository: $ROOT"

    # Support the repository-standard AGENT.MD and the requested agents.md spelling.
    for file in "$ROOT/AGENT.MD" "$ROOT/agents.md" "$ROOT/AGENTS.md"; do
        if [ -f "$file" ]; then
            rel=${file#"$ROOT/"}
            printf '%s\n' '================================================================'
            printf '%s\n' "FILE: $rel"
            printf '%s\n' '================================================================'
            cat "$file"
            printf '\n\n'
        fi
    done

    # Include docs and source in deterministic order. Do not include the generated export itself.
    find "$ROOT/src" "$ROOT/docs" \
        -type f \
        ! -path '*/.DS_Store' \
        ! -iname '*.png' \
        2>/dev/null | LC_ALL=C sort | while IFS= read -r file; do
        [ "$file" = "$OUTPUT" ] && continue
        rel=${file#"$ROOT/"}
        printf '%s\n' '================================================================'
        printf '%s\n' "FILE: $rel"
        printf '%s\n' '================================================================'
        cat "$file"
        printf '\n\n'
    done
} > "$TMP_OUTPUT"

mv "$TMP_OUTPUT" "$OUTPUT"
trap - EXIT HUP INT TERM

printf 'Wrote ChatGPT context: %s\n' "$OUTPUT"
printf 'Size: %s bytes\n' "$(wc -c < "$OUTPUT" | tr -d ' ')"
