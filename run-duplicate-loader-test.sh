#!/bin/sh
# run-duplicate-loader-test.sh -- run the one-JAR / multiple-loader duplication guard harness.
#
# ProcessInitializationGuardTest is a plain-main harness (no JUnit dependency), so it is not
# picked up by mvn test. It loads the mod through two isolated classloaders to prove that a host
# with more than one loader installed cannot initialize the mod twice. This script compiles it
# with the project toolchain and classpath and runs it.
#
# Usage:
#   ./run-duplicate-loader-test.sh
#   JAVA=/path/to/java ./run-duplicate-loader-test.sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"

MVN=${MVN:-mvn}
TEST_CLASSES="$PROJECT_DIR/target/test-classes"
MAIN_CLASS=com.asbestosstar.nativeaccelerator.platform.ProcessInitializationGuardTest

# The project targets a recent Java release, so the harness must run on the same JDK Maven compiles
# with rather than whatever java happens to be first on PATH. Explicit JAVA wins; otherwise ask Maven.
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
    echo "run-duplicate-loader-test.sh: no java runtime found (set JAVA=...)" >&2
    exit 1
fi

echo "[duplicate-loader] compiling main and test sources..."
$MVN -o -q test-compile

CP="$TEST_CLASSES:$PROJECT_DIR/target/classes"

echo "[duplicate-loader] running $MAIN_CLASS with $JAVA"
exec "$JAVA" --enable-native-access=ALL-UNNAMED -cp "$CP" "$MAIN_CLASS"
