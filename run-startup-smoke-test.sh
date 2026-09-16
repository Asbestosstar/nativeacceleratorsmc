#!/bin/sh
# Deterministic tests for test-macos.sh smoke mode; never launches Minecraft.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TMP=$(mktemp -d "${TMPDIR:-/tmp}/nativeaccelerator-smoke.XXXXXX")
trap 'rm -rf "$TMP"' EXIT

FAKE_JAVA="$TMP/fake-java.sh"
cat >"$FAKE_JAVA" <<'EOF'
#!/bin/sh
case "${FAKE_STARTUP_MODE:-ready}" in
  ready) printf '%s\n' 'Created window using SDL video driver: test' ;;
  fatal) printf '%s\n' 'NoSuchMethodError: simulated startup failure' ;;
  quiet) : ;;
  delayed) sleep "${FAKE_DELAY:-3}"; printf '%s\n' 'Created window using SDL video driver: test' ;;
esac
while :; do sleep 1; done
EOF
chmod +x "$FAKE_JAVA"

if [ ! -f "$ROOT/target/native-accelerator-0.1.0-SNAPSHOT.jar" ]; then
  echo "run-startup-smoke-test.sh: build the project first" >&2
  exit 1
fi

run_case() {
  name=$1
  expected=$2
  mode=$3
  timeout=$4
  max_seconds=$5
  delay=$6
  mc_dir="$TMP/minecraft-$name"
  log="$TMP/$name.log"
  output="$TMP/$name.out"
  mkdir -p "$mc_dir"
  status=0
  (
    export MC_DIR="$mc_dir"
    export JAVA="$FAKE_JAVA"
    export SKIP_BUILD=1
    export NO_BUNDLE=1
    export SMOKE_TEST=1
    export STARTUP_TIMEOUT="$timeout"
    export STARTUP_LOG="$log"
    export FAKE_STARTUP_MODE="$mode"
    export FAKE_DELAY="$delay"
    if [ -n "$max_seconds" ]; then export STARTUP_MAX_SECONDS="$max_seconds"; else unset STARTUP_MAX_SECONDS; fi
    "$ROOT/test-macos.sh"
  ) >"$output" 2>&1 || status=$?
  if [ "$status" -ne "$expected" ]; then
    echo "FAIL $name: expected status $expected, got $status" >&2
    cat "$output" >&2
    exit 1
  fi
  echo "ok   $name (status $status)"
}

run_case readiness 0 ready 5 '' 0
run_case timeout 1 quiet 1 '' 0
run_case budget 1 delayed 5 1 2
run_case fatal 1 fatal 5 '' 0
echo 'RESULT: PASS (4 smoke cases)'
