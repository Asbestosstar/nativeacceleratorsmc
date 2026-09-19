#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/target/smt-layout-test"
rm -rf "$OUT"
mkdir -p "$OUT"
javac -d "$OUT" \
  "$ROOT/src/main/java/com/asbestosstar/nativeaccelerator/platform/CpuCoreLayout.java" \
  "$ROOT/src/main/java/com/asbestosstar/nativeaccelerator/platform/CpuTopologyLayout.java" \
  "$ROOT/src/test/java/com/asbestosstar/nativeaccelerator/platform/CpuTopologyLayoutTest.java"
java -cp "$OUT" com.asbestosstar.nativeaccelerator.platform.CpuTopologyLayoutTest
