package com.asbestosstar.nativeaccelerator.kernels;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.IntPredicate;

/**
 * Optional bridge to Oracle's historical DAX-accelerated IntStream-compatible library.
 *
 * <p>The dependency is intentionally discovered at runtime so the universal Native Accelerator JAR
 * does not acquire a hard linkage on Solaris/SPARC-only classes. When present, operations are created
 * with {@code DaxIntStream.of(int[]).parallel()} because Oracle's implementation only considers parallel
 * streams for DAX offload. If the optional JAR is absent or becomes linkage-incompatible on the current
 * JVM, callers can fall back to the native libdax adapter or ordinary Java.</p>
 */
final class DaxIntStreamAdapter {
    private static final String[] CLASS_NAMES = {
            "com.oracle.stream.DaxIntStream",
            // Some early collateral used this capitalization. Supporting it costs nothing and makes
            // optional old developer-cloud drops easier to consume.
            "com.Oracle.Stream.DaxIntStream"
    };

    private static volatile Binding binding;
    private static volatile boolean permanentlyFailed;

    private DaxIntStreamAdapter() {}

    static boolean available() {
        return binding() != null;
    }

    static String className() {
        Binding current = binding();
        return current == null ? "" : current.type.getName();
    }

    static long countBetween(int[] values, int lowerInclusive, int upperInclusive) throws Throwable {
        IntPredicate predicate = between(lowerInclusive, upperInclusive);
        Binding b = requireBinding();
        Object stream = b.parallel.invoke(b.of.invoke(null, (Object) values));
        Object filtered = b.filter.invoke(stream, predicate);
        return ((Number) b.count.invoke(filtered)).longValue();
    }

    static int[] filterBetween(int[] values, int lowerInclusive, int upperInclusive) throws Throwable {
        IntPredicate predicate = between(lowerInclusive, upperInclusive);
        Binding b = requireBinding();
        Object stream = b.parallel.invoke(b.of.invoke(null, (Object) values));
        Object filtered = b.filter.invoke(stream, predicate);
        return (int[]) b.toArray.invoke(filtered);
    }

    static boolean anyBetween(int[] values, int lowerInclusive, int upperInclusive) throws Throwable {
        Binding b = requireBinding();
        Object stream = b.parallel.invoke(b.of.invoke(null, (Object) values));
        return (Boolean) b.anyMatch.invoke(stream, between(lowerInclusive, upperInclusive));
    }

    static boolean allBetween(int[] values, int lowerInclusive, int upperInclusive) throws Throwable {
        Binding b = requireBinding();
        Object stream = b.parallel.invoke(b.of.invoke(null, (Object) values));
        return (Boolean) b.allMatch.invoke(stream, between(lowerInclusive, upperInclusive));
    }

    static boolean noneBetween(int[] values, int lowerInclusive, int upperInclusive) throws Throwable {
        Binding b = requireBinding();
        Object stream = b.parallel.invoke(b.of.invoke(null, (Object) values));
        return (Boolean) b.noneMatch.invoke(stream, between(lowerInclusive, upperInclusive));
    }

    static void markFailed(Throwable failure) {
        permanentlyFailed = true;
        binding = null;
        if (Boolean.getBoolean("nativeaccelerator.dax.debug")) {
            Throwable cause = failure instanceof InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause() : failure;
            System.err.println("[Native Accelerator] Optional DaxIntStream backend disabled after failure: " + cause);
        }
    }

    private static IntPredicate between(int lowerInclusive, int upperInclusive) {
        return value -> value >= lowerInclusive && value <= upperInclusive;
    }

    private static Binding requireBinding() {
        Binding current = binding();
        if (current == null) throw new IllegalStateException("DaxIntStream is unavailable");
        return current;
    }

    private static Binding binding() {
        Binding current = binding;
        if (current != null) return current;
        if (permanentlyFailed) return null;
        synchronized (DaxIntStreamAdapter.class) {
            current = binding;
            if (current != null) return current;
            if (permanentlyFailed) return null;
            ClassLoader loader = DaxIntStreamAdapter.class.getClassLoader();
            for (String name : CLASS_NAMES) {
                try {
                    Class<?> type = Class.forName(name, false, loader);
                    Method of = type.getMethod("of", int[].class);
                    Method parallel = type.getMethod("parallel");
                    Method filter = type.getMethod("filter", IntPredicate.class);
                    Method count = type.getMethod("count");
                    Method toArray = type.getMethod("toArray");
                    Method anyMatch = type.getMethod("anyMatch", IntPredicate.class);
                    Method allMatch = type.getMethod("allMatch", IntPredicate.class);
                    Method noneMatch = type.getMethod("noneMatch", IntPredicate.class);
                    current = new Binding(type, of, parallel, filter, count, toArray,
                            anyMatch, allMatch, noneMatch);
                    binding = current;
                    return current;
                } catch (ClassNotFoundException ignored) {
                    // Try the next known package spelling.
                } catch (ReflectiveOperationException | LinkageError incompatible) {
                    markFailed(incompatible);
                    return null;
                }
            }
            return null;
        }
    }

    private record Binding(Class<?> type, Method of, Method parallel, Method filter,
                           Method count, Method toArray, Method anyMatch,
                           Method allMatch, Method noneMatch) {}
}
