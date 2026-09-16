#!/bin/sh
# run-startup-timer-test.sh -- run the Native Accelerator startup-timing harness.
#
# StartupTimerTest is a plain-main harness (no JUnit in the local repository), so it is not
# picked up by `mvn test`. This script compiles it with the project toolchain and classpath
# and runs it.
#
# It guards the behaviour the startup Mixins depend on:
#   - the first occurrence of a stage or milestone wins (comparable runs);
#   - StartupTimer.finish(...) reports exactly once, which is what keeps the server-ready
#     probe safe on a field write that executes every tick;
#   - invalid stage names never throw.
#
# Usage:
#   ./run-startup-timer-test.sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

MVN=${MVN:-mvn}
CP_FILE="$PROJECT_DIR/target/startup-timer-test-classpath.txt"
TEST_CLASSES="$PROJECT_DIR/target/test-classes"
MAIN_CLASS=com.asbestosstar.nativeaccelerator.startup.StartupTimerTest

# The project targets a recent Java release, so the harness must run on the same JDK Maven
# compiles with rather than whatever java happens to be first on PATH.
if [ -z "${JAVA:-}" ]; then
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA="$JAVA_HOME/bin/java"
    else
        JAVA=$($MVN -o -version 2>/dev/null | sed -n 's/^.*runtime: //p')
        JAVA=${JAVA%/}/bin/java
    fi
fi
if [ ! -x "$JAVA" ]; then
    JAVA=$(command -v java || true)
fi
if [ -z "$JAVA" ]; then
    echo "run-startup-timer-test.sh: no java runtime found (set JAVA=...)" >&2
    exit 1
fi

echo "[startup-timer] compiling main and test sources..."
$MVN -o -q test-compile

echo "[startup-timer] resolving runtime classpath..."
$MVN -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=compile

CP="$TEST_CLASSES:$PROJECT_DIR/target/classes:$(cat "$CP_FILE")"

echo "[startup-timer] running $MAIN_CLASS"
exec "$JAVA" -cp "$CP" "$MAIN_CLASS" "$@"
