#!/bin/sh
# run-bit-storage-harness.sh -- run the Native Accelerator bit-storage harness.
#
# BitStorageHarness is a plain-main harness (no JUnit dependency), so it is not picked
# up by `mvn test`. This script compiles it with the project toolchain and classpath and
# runs it. It performs two jobs:
#
#   1. Differential correctness: the native SimpleBitStorage kernel is compared against a
#      transcription of the vanilla unpack(int[]) loop for every 1..32 bit width.
#   2. Crossover benchmark: times the Java loop against the full native staging path and
#      prints a suggested DEFAULT_MIN_ELEMENTS value for the size gate.
#
# The native libraries must already be built (mvn -Pnative ... or ./test-macos.sh), because
# without them the harness reports SKIP the same way the game would fall back to Java.
#
# Usage:
#   ./run-bit-storage-harness.sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

MVN=${MVN:-mvn}
CP_FILE="$PROJECT_DIR/target/bit-storage-harness-classpath.txt"
TEST_CLASSES="$PROJECT_DIR/target/test-classes"
MAIN_CLASS=com.asbestosstar.nativeaccelerator.kernels.BitStorageHarness

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
    echo "run-bit-storage-harness.sh: no java runtime found (set JAVA=...)" >&2
    exit 1
fi

echo "[bits] compiling main and test sources..."
$MVN -o -q test-compile

echo "[bits] resolving runtime classpath..."
$MVN -o -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=compile

CP="$TEST_CLASSES:$PROJECT_DIR/target/classes:$(cat "$CP_FILE")"

echo "[bits] running $MAIN_CLASS"
exec "$JAVA" -cp "$CP" "$MAIN_CLASS" "$@"
