#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/nativeaccelerator-dax-stream-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/src/com/oracle/stream" "$TMP/src/com/asbestosstar/nativeaccelerator/kernels" "$TMP/classes"
cp "$ROOT/src/main/java/com/asbestosstar/nativeaccelerator/kernels/DaxIntStreamAdapter.java" \
   "$TMP/src/com/asbestosstar/nativeaccelerator/kernels/"

cat > "$TMP/src/com/oracle/stream/DaxIntStream.java" <<'JAVA'
package com.oracle.stream;

import java.util.Arrays;
import java.util.function.IntPredicate;

/** Test-only stand-in exposing the published DaxIntStream Stream-like surface. */
public final class DaxIntStream {
    private final int[] values;
    private boolean parallel;

    private DaxIntStream(int[] values) { this.values = values; }

    public static DaxIntStream of(int[] values) { return new DaxIntStream(values); }

    public DaxIntStream parallel() {
        this.parallel = true;
        return this;
    }

    private void requireParallel() {
        if (!parallel) throw new AssertionError("Native Accelerator must mark DaxIntStream parallel for DAX offload");
    }

    public DaxIntStream filter(IntPredicate predicate) {
        requireParallel();
        DaxIntStream out = new DaxIntStream(Arrays.stream(values).filter(predicate).toArray());
        out.parallel = true;
        return out;
    }

    public long count() { requireParallel(); return values.length; }
    public int[] toArray() { requireParallel(); return values.clone(); }
    public boolean anyMatch(IntPredicate p) { requireParallel(); return Arrays.stream(values).anyMatch(p); }
    public boolean allMatch(IntPredicate p) { requireParallel(); return Arrays.stream(values).allMatch(p); }
    public boolean noneMatch(IntPredicate p) { requireParallel(); return Arrays.stream(values).noneMatch(p); }
}
JAVA

cat > "$TMP/src/com/asbestosstar/nativeaccelerator/kernels/DaxIntStreamAdapterTest.java" <<'JAVA'
package com.asbestosstar.nativeaccelerator.kernels;

import java.util.Arrays;

public final class DaxIntStreamAdapterTest {
    public static void main(String[] args) throws Throwable {
        int[] values = {-20, -10, -1, 0, 1, 10, 11, 100};
        check(DaxIntStreamAdapter.available(), "optional class discovered");
        check("com.oracle.stream.DaxIntStream".equals(DaxIntStreamAdapter.className()), "class name");
        check(DaxIntStreamAdapter.countBetween(values, -10, 10) == 5L, "filter/count");
        check(Arrays.equals(DaxIntStreamAdapter.filterBetween(values, -10, 10),
                new int[] {-10, -1, 0, 1, 10}), "filter/toArray");
        check(DaxIntStreamAdapter.anyBetween(values, 99, 101), "anyMatch");
        check(!DaxIntStreamAdapter.allBetween(values, -10, 10), "allMatch");
        check(DaxIntStreamAdapter.noneBetween(values, 200, 300), "noneMatch");
        System.out.println("DaxIntStream adapter checks passed: 7/7");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
JAVA

javac -d "$TMP/classes" $(find "$TMP/src" -name '*.java' -print)
java -cp "$TMP/classes" com.asbestosstar.nativeaccelerator.kernels.DaxIntStreamAdapterTest
