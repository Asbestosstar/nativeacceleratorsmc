#!/usr/bin/env bash
# Paired cold-start benchmark for ~/Downloads/nativeserver0 (Minecraft 26.3 / Fabric).
# Uses isolated instances under target/ and never changes the supplied server directory.
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SETUP="$ROOT/scripts/setup-nativeserver0-benchmark.sh"
BENCH_ROOT=${NATIVESERVER0_BENCH_ROOT:-"$ROOT/target/nativeserver0-benchmark"}
RESULTS_DIR=${NATIVESERVER0_RESULTS_DIR:-"$ROOT/target/nativeserver0-results"}
RUNS=${1:-3}
TIMEOUT_SECONDS=${NATIVESERVER0_TIMEOUT_SECONDS:-180}
JAVA_BIN=${NATIVESERVER0_JAVA:-java}
SEED=${NATIVESERVER0_SEED:--4630982771278649347}
JAVA_FLAGS=${NATIVESERVER0_JAVA_FLAGS:--Xms2G -Xmx2G}
if ! [[ "$RUNS" =~ ^[1-9][0-9]*$ ]]; then echo "Usage: $0 [positive-run-count]" >&2; exit 64; fi
if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then echo "NATIVESERVER0_JAVA=$JAVA_BIN is not executable." >&2; exit 69; fi
JAVA_VERSION=$("$JAVA_BIN" -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')
if [[ -z "$JAVA_VERSION" || "$JAVA_VERSION" -lt 25 ]]; then echo "Minecraft 26.3 requires Java 25." >&2; exit 69; fi
mkdir -p "$RESULTS_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
RESULTS="$RESULTS_DIR/startup-$STAMP.tsv"
printf '# Native Accelerator nativeserver0 paired cold-start benchmark v1\n# seed=%s\n# java=%s\n# javaFlags=%s\n# timeoutSeconds=%s\nrun\tmode\twallReadyMs\tserverReadyMs\tserverInitMs\tstatus\tlog\n' "$SEED" "$JAVA_BIN" "$JAVA_FLAGS" "$TIMEOUT_SECONDS" > "$RESULTS"
now_ms() { perl -MTime::HiRes=time -e 'printf "%.0f\n", time * 1000'; }
metric() { [[ -f "$1" ]] || return 0; awk -F '\t' -v stage="$2" -v column="$3" '$1 == stage { print $column; exit }' "$1"; }
run_one() {
  local mode index instance log report pid start now deadline wall ready init status
  mode=$1
  index=$2
  instance="$BENCH_ROOT/$mode"
  "$SETUP" "$mode" >/dev/null
  log="$instance/benchmark-$mode-$index.log"
  report="$instance/logs/nativeaccelerator-startup-times.tsv"
  rm -f "$log" "$report"
  start=$(now_ms)
  (cd "$instance" && exec "$JAVA_BIN" $JAVA_FLAGS -Dnativeaccelerator.startup.timing=true -Dnativeaccelerator.startup.report="$report" -Dnativeaccelerator.startup.baseline=none -jar fabric-server-launch.jar nogui) >"$log" 2>&1 &
  pid=$!
  deadline=$((start + TIMEOUT_SECONDS * 1000))
  status=timeout
  while kill -0 "$pid" 2>/dev/null; do
    if [[ "$mode" == native ]] && [[ -f "$report" ]] && grep -q $'^server.ready\tmilestone\t' "$report"; then status=ready; break; fi
    if [[ "$mode" == baseline ]] && grep -q 'Done (.*)! For help, type "help"' "$log"; then status=ready; break; fi
    now=$(now_ms)
    if (( now >= deadline )); then break; fi
    sleep 0.10
  done
  now=$(now_ms)
  wall=$((now - start))
  kill -TERM "$pid" 2>/dev/null || true
  for _ in $(seq 1 50); do kill -0 "$pid" 2>/dev/null || break; sleep 0.10; done
  kill -KILL "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  ready=$(metric "$report" server.ready 5 || true)
  init=$(metric "$report" server.init 4 || true)
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$index" "$mode" "$wall" "${ready:-}" "${init:-}" "$status" "$log" >> "$RESULTS"
  printf '%s run %s: %s (%s ms)\n' "$mode" "$index" "$status" "$wall"
  [[ "$status" == ready ]]
}
for run in $(seq 1 "$RUNS"); do run_one baseline "$run"; run_one native "$run"; done
printf '\nResults: %s\n' "$RESULTS"
awk -F '\t' 'NR > 6 && $6 == "ready" { sum[$2] += $3; n[$2]++ } END { for (mode in sum) printf "%s mean wall-ready: %.1f ms (%d successful runs)\n", mode, sum[mode] / n[mode], n[mode] }' "$RESULTS" | sort
