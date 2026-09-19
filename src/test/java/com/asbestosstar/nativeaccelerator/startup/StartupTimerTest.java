package com.asbestosstar.nativeaccelerator.startup;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Plain-main verification for {@link StartupTimer}. There is no JUnit in the local repository, so the
 * assertions are explicit and a failure prints exactly which behaviour regressed.
 *
 * <p>This harness exists because {@link StartupTimer} is called from Mixin handlers whose injection points
 * are not all entered once. The clearest case is the server-ready milestone:
 * {@code MinecraftServer#runServer()} writes {@code this.isReady = true;} at the end of every tick, so the
 * field-write injection in {@code DedicatedServerReadyMixin} runs about twenty times a second.
 * {@link StartupTimer#finish(String)} must therefore be idempotent, and the check named
 * {@code second finish prints nothing} is the regression guard for that.</p>
 *
 * <p>Run it through {@code ./run-startup-timer-test.sh}.</p>
 */
public final class StartupTimerTest {

    private static final String ABSENT_STAGE = "test.stage-that-was-never-started";

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        // Keep the harness hermetic: never touch the real report/baseline files under logs/.
        System.setProperty(StartupTimer.REPORT_PROPERTY, StartupTimer.DISABLED_VALUE);
        System.setProperty(StartupTimer.BASELINE_PROPERTY, "target/startup-timer-test-baseline-absent.tsv");
        System.setProperty(StartupTimer.RECORD_PROPERTY, "false");

        enabledFlagRespectsProperty();
        stagesKeepFirstOccurrence();
        milestonesKeepFirstMark();
        finishReportsExactlyOnce();
        finishDoesNothingWhenDisabled();
        lifetimeStagesAreExcludedFromRanking();
        helpersReportAbsence();
        invalidStagesAreIgnored();
        resetClearsRecordedState();

        System.out.println("checks=" + checks + " failures=" + failures);
        if (failures != 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /* ------------------------------------------------------------------ groups ------------------- */

    private static void enabledFlagRespectsProperty() {
        System.setProperty(StartupTimer.ENABLED_PROPERTY, "false");
        check("timing disabled by property", !StartupTimer.enabled());

        System.setProperty(StartupTimer.ENABLED_PROPERTY, "true");
        check("timing enabled by property", StartupTimer.enabled());

        System.setProperty(StartupTimer.ENABLED_PROPERTY, "   ");
        check("blank timing property reads as disabled", !StartupTimer.enabled());

        System.setProperty(StartupTimer.ENABLED_PROPERTY, "true");
    }

    private static void stagesKeepFirstOccurrence() {
        StartupTimer.resetForTests();

        StartupTimer.begin("test.stage");
        sleepMillis(15L);
        StartupTimer.end("test.stage");
        long first = StartupTimer.elapsedMillis("test.stage");
        check("finished stage reports a duration", first >= 0L);

        // A second begin/end pair must not move the first occurrence: startup numbers have to stay
        // comparable across a later repeat (a second resource reload, a second world load).
        sleepMillis(15L);
        StartupTimer.begin("test.stage");
        sleepMillis(15L);
        StartupTimer.end("test.stage");
        long second = StartupTimer.elapsedMillis("test.stage");
        checkEquals("stage keeps its first duration", Long.valueOf(first), Long.valueOf(second));
    }

    private static void milestonesKeepFirstMark() {
        StartupTimer.resetForTests();

        StartupTimer.mark(StartupStages.SERVER_READY);
        long first = StartupTimer.millisSinceProcessStart(StartupStages.SERVER_READY);
        check("milestone reports milliseconds since process start", first >= 0L);

        // This is the server-ready case: the probe fires on every tick and only the first instant counts.
        sleepMillis(25L);
        StartupTimer.mark(StartupStages.SERVER_READY);
        long second = StartupTimer.millisSinceProcessStart(StartupStages.SERVER_READY);
        checkEquals("milestone keeps its first instant", Long.valueOf(first), Long.valueOf(second));
    }

    private static void finishReportsExactlyOnce() {
        StartupTimer.resetForTests();
        StartupTimer.mark(StartupStages.SERVER_READY);

        String first = capture(() -> StartupTimer.finish("first"));
        check("first finish prints the report", first.contains("[Native Accelerator] startup timing"));
        check("first finish names its reason", first.contains("first"));

        String second = capture(() -> StartupTimer.finish("second"));
        check("second finish prints nothing", second.isEmpty());

        // And a third, to be sure the guard is a stable flag rather than a one-shot toggle.
        String third = capture(() -> StartupTimer.finish("third"));
        check("later finish calls print nothing", third.isEmpty());
    }

    private static void finishDoesNothingWhenDisabled() {
        StartupTimer.resetForTests();
        StartupTimer.mark(StartupStages.SERVER_READY);

        System.setProperty(StartupTimer.ENABLED_PROPERTY, "false");
        String out = capture(() -> StartupTimer.finish("disabled"));
        check("finish prints nothing when timing is disabled", out.isEmpty());
        System.setProperty(StartupTimer.ENABLED_PROPERTY, "true");

        // The disabled call must not have consumed the run-once slot.
        String after = capture(() -> StartupTimer.finish("enabled again"));
        check("finish still reports after a disabled call", after.contains("[Native Accelerator] startup timing"));
    }

    private static void lifetimeStagesAreExcludedFromRanking() {
        StartupTimer.resetForTests();
        StartupTimer.beginLifetime("test.lifetime");
        sleepMillis(15L);
        StartupTimer.endLifetime("test.lifetime");
        StartupTimer.begin("test.ranked");
        sleepMillis(5L);
        StartupTimer.end("test.ranked");

        String report = StartupTimer.report();
        check("report lists the lifetime stage", report.contains("test.lifetime"));
        check("report has a lifetime section", report.contains("lifetime stages"));
        check("report lists the ranked stage", report.contains("test.ranked"));

        // "The whole session" would swamp every real stage, so a lifetime stage must not be ranked.
        int ranking = report.indexOf("slowest stages");
        check("report has a slowest-stages section", ranking >= 0);
        if (ranking >= 0) {
            String slowest = report.substring(ranking);
            check("lifetime stage is absent from the ranking", !slowest.contains("test.lifetime"));
            check("ranked stage is present in the ranking", slowest.contains("test.ranked"));
        }
    }

    private static void helpersReportAbsence() {
        StartupTimer.resetForTests();
        checkEquals("absent milestone reports -1", Long.valueOf(-1L),
                Long.valueOf(StartupTimer.millisSinceProcessStart(ABSENT_STAGE)));
        checkEquals("absent stage reports -1", Long.valueOf(-1L),
                Long.valueOf(StartupTimer.elapsedMillis(ABSENT_STAGE)));
        checkEquals("null milestone reports -1", Long.valueOf(-1L),
                Long.valueOf(StartupTimer.millisSinceProcessStart(null)));
        checkEquals("null stage reports -1", Long.valueOf(-1L),
                Long.valueOf(StartupTimer.elapsedMillis(null)));

        StartupTimer.begin("test.open");
        checkEquals("unfinished stage reports -1", Long.valueOf(-1L),
                Long.valueOf(StartupTimer.elapsedMillis("test.open")));
    }

    private static void invalidStagesAreIgnored() {
        StartupTimer.resetForTests();

        // None of these may throw: reporting must not be able to break the game it measures.
        StartupTimer.mark(null);
        StartupTimer.mark("");
        StartupTimer.mark("   ");
        StartupTimer.begin(null);
        StartupTimer.begin("");
        StartupTimer.end(null);
        StartupTimer.end(ABSENT_STAGE);

        String report = StartupTimer.report();
        check("report survives invalid stage names", report.contains("[Native Accelerator] startup timing"));
        check("invalid stage names record nothing", report.contains("no stages recorded"));
    }

    private static void resetClearsRecordedState() {
        StartupTimer.resetForTests();
        StartupTimer.begin("test.cleared");
        StartupTimer.end("test.cleared");
        StartupTimer.mark(StartupStages.SERVER_READY);

        StartupTimer.resetForTests();
        checkEquals("reset clears stages", Long.valueOf(-1L),
                Long.valueOf(StartupTimer.elapsedMillis("test.cleared")));
        checkEquals("reset clears milestones", Long.valueOf(-1L),
                Long.valueOf(StartupTimer.millisSinceProcessStart(StartupStages.SERVER_READY)));

        // resetForTests must also clear the run-once report flag, or a harness could only report once.
        String out = capture(() -> StartupTimer.finish("after reset"));
        check("reset clears the run-once report flag", out.contains("[Native Accelerator] startup timing"));
    }

    /* ------------------------------------------------------------------ helpers ------------------ */

    private static String capture(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void check(String what, boolean condition) {
        checks++;
        if (condition) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }

    private static void checkEquals(String what, Object expected, Object actual) {
        checks++;
        if (java.util.Objects.equals(expected, actual)) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private StartupTimerTest() {}
}


