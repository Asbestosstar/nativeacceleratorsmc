#!/usr/bin/env sh
set -eu

if [ "$#" -lt 1 ]; then
  echo "usage: $0 <benchmark-command> [args ...]" >&2
  echo "Runs the exact command five times with SMT occupancy 1,2,4,6,8." >&2
  echo "The benchmark command must create its own fresh world / fixed-seed workload." >&2
  exit 2
fi

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=${NATIVEACCELERATOR_SMT_SWEEP_OUT:-"$ROOT/target/worldgen-smt-sweep"}
RESERVE=${NATIVEACCELERATOR_SMT_RESERVE_CORES:-1}
BIND=${NATIVEACCELERATOR_SMT_BIND:-true}
mkdir -p "$OUT"

for SMT in 1 2 4 6 8; do
  LOG="$OUT/smt${SMT}.log"
  EXTRA="-Dnativeaccelerator.worldgen.smtScheduler=true -Dnativeaccelerator.worldgen.smt.strandsPerCore=$SMT -Dnativeaccelerator.worldgen.smt.reserveCores=$RESERVE -Dnativeaccelerator.worldgen.smt.bind=$BIND -Dnativeaccelerator.worldgen.deepProfile=false"
  echo "=== SMT $SMT strands/core ==="
  echo "JAVA_TOOL_OPTIONS additions: $EXTRA"
  # Preserve caller flags exactly and append only the SMT policy under test.
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $EXTRA" \
    NATIVEACCELERATOR_SMT_STRANDS_PER_CORE="$SMT" \
    "$@" 2>&1 | tee "$LOG"
done

echo "SMT sweep logs: $OUT"
