package com.asbestosstar.nativeaccelerator.platform;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Objects;

/**
 * Verifies that a host with more than one loader installed cannot initialise the mod twice.
 *
 * The decisive case is two loaders that each loaded their own copy of the mod classes, because then
 * ordinary static state is per-classloader and cannot detect the duplicate. This test therefore loads
 * ProcessInitializationGuard through two isolated classloaders and checks that the second sees the first
 * one claim.
 */
public final class ProcessInitializationGuardTest {

    private static final String KEY = "nativeaccelerator.test.process.key";

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        ProcessInitializationGuard.release(KEY);

        // --- same-classloader semantics -------------------------------------------------
        expect("first claim wins", true, ProcessInitializationGuard.claim(KEY));
        expect("second claim in same loader loses", false, ProcessInitializationGuard.claim(KEY));
        expect("claim is recorded", true, ProcessInitializationGuard.claimed(KEY));
        String described = ProcessInitializationGuard.claimDescription(KEY);
        expect("claim description names the claimer", true, described != null && described.contains("loader="));

        ProcessInitializationGuard.release(KEY);
        expect("released key can be claimed again", true, ProcessInitializationGuard.claim(KEY));
        ProcessInitializationGuard.release(KEY);

        // --- cross-classloader semantics: the real duplicate-loader scenario ------------
        // Locate the compiled mod classes through the classpath so the test does not depend on the working
        // directory it happens to be launched from.
        File root = compiledModClassesDirectory();
        expect("mod classes are reachable", true, root != null && root.isDirectory());
        expect("guard class is inside that directory", true,
                new File(root, "com/asbestosstar/nativeaccelerator/platform/ProcessInitializationGuard.class").isFile());

        // Two fully isolated loaders, one per simulated loader. Each holds its own copy of every mod
        // class, which is what happens when two loaders both load the universal JAR.
        try (URLClassLoader loaderA = newIsolated(root); URLClassLoader loaderB = newIsolated(root)) {
            expect("isolated loaders are distinct", true, loaderA != loaderB);

            Class<?> guardA = Class.forName(ProcessInitializationGuard.class.getName(), true, loaderA);
            Class<?> guardB = Class.forName(ProcessInitializationGuard.class.getName(), true, loaderB);
            expect("loaders hold distinct guard classes", true, guardA != guardB);

            Object a = guardA.getMethod("claim", String.class).invoke(null, KEY);
            expect("loader A claims the key", true, a);
            Object b = guardB.getMethod("claim", String.class).invoke(null, KEY);
            expect("loader B stands down using no shared state of its own", false, b);

            Object seenByB = guardB.getMethod("claimDescription", String.class).invoke(null, KEY);
            expect("loader B can read loader A claim", true, seenByB != null && seenByB.toString().contains("loader="));
        }

        ProcessInitializationGuard.release(KEY);

        // --- realistic double entrypoint: two loaders both calling initialize() ---------
        try (URLClassLoader loaderA = newIsolated(root); URLClassLoader loaderB = newIsolated(root)) {
            Class<?> modA = Class.forName("com.asbestosstar.nativeaccelerator.NativeAccelerator", true, loaderA);
            Class<?> modB = Class.forName("com.asbestosstar.nativeaccelerator.NativeAccelerator", true, loaderB);
            expect("loaders hold distinct mod classes", true, modA != modB);

            modA.getMethod("initialize").invoke(null);
            String afterFirst = System.getProperty(ProcessInitializationGuard.MOD_INITIALIZATION);
            expect("first loader initializes the mod", true, afterFirst != null);

            modB.getMethod("initialize").invoke(null);
            expect("second loader does not re-initialize the mod", true,
                    Objects.equals(afterFirst, System.getProperty(ProcessInitializationGuard.MOD_INITIALIZATION)));
            expect("mod initialization stays claimed", true,
                    ProcessInitializationGuard.claimed(ProcessInitializationGuard.MOD_INITIALIZATION));
        }

        System.out.println();
        System.out.println("checks=" + checks + " failures=" + failures);
        System.out.println("RESULT: " + (failures == 0 ? "PASS" : "FAIL"));
        if (failures != 0) System.exit(1);
    }

    /** Directory holding the compiled mod classes, found via the classpath rather than the CWD. */
    private static File compiledModClassesDirectory() throws Exception {
        URL resource = ProcessInitializationGuardTest.class.getClassLoader()
                .getResource("com/asbestosstar/nativeaccelerator/platform/ProcessInitializationGuard.class");
        if (resource == null) return null;
        File file = new File(resource.toURI());
        // file is .../com/asbestosstar/nativeaccelerator/platform/ProcessInitializationGuard.class
        return file.getParentFile().getParentFile().getParentFile().getParentFile().getParentFile();
    }

    private static URLClassLoader newIsolated(File root) throws Exception {
        return new URLClassLoader(new URL[] {root.toURI().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static void expect(String what, Object expected, Object actual) {
        checks++;
        if (Objects.equals(expected, actual)) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private ProcessInitializationGuardTest() {}
}
