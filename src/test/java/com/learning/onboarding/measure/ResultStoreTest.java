package com.learning.onboarding.measure;

import static org.assertj.core.api.Assertions.assertThat;

import com.learning.onboarding.domain.ReviewArea;
import com.learning.onboarding.domain.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The durability guarantee, tested rather than asserted.
 *
 * <p>Two days of measurement were lost because the only record of the
 * experiment was a Postgres container that got removed. These tests exist to
 * make sure the replacement actually writes something that survives - the whole
 * point is that it works on the day the database does not.
 */
@DisplayName("measurement results survive the database")
class ResultStoreTest {

    private static Fixture fixture(String id, boolean clean, int seeded) {
        var defects = new java.util.ArrayList<Fixture.ExpectedDefect>();
        for (int i = 0; i < seeded; i++) {
            defects.add(new Fixture.ExpectedDefect(ReviewArea.COMPLIANCE,
                    Severity.MAJOR, null, List.of("expired"), "planted " + i));
        }
        return new Fixture(id, "test fixture " + id, clean, null, List.copyOf(defects));
    }

    private static MeasurementHarness.Result result(String label) {
        var withDefects = fixture("F01", false, 2);
        var alsoDefective = fixture("F02", false, 1);
        var clean = fixture("F03", true, 0);

        return new MeasurementHarness.Result(label, List.of(
                new MeasurementHarness.FixtureOutcome(withDefects,
                        withDefects.expected(), List.of(), withDefects.expected(),
                        List.of(), 0, 0, true, 1200L),
                new MeasurementHarness.FixtureOutcome(alsoDefective,
                        List.of(), alsoDefective.expected(), List.of(),
                        List.of(), 1, 0, true, 1400L),
                new MeasurementHarness.FixtureOutcome(clean,
                        List.of(), List.of(), List.of(), List.of(), 0, 1, true, 900L)),
                true);
    }

    @Test
    @DisplayName("a completed configuration is on disk immediately")
    void writesOneFilePerConfiguration(@TempDir Path dir) {
        new ResultStore(dir).save(2, result("gate + four agents"));

        Path file = dir.resolve("config-2.json");
        assertThat(file).exists();
        assertThat(file).content()
                .contains("\"configuration\": 2")
                .contains("gate + four agents")
                // 2 of 3 planted defects caught
                .contains("\"seeded\": 3")
                .contains("\"caught\": 2");
    }

    @Test
    @DisplayName("recall is written locale-independently")
    void recallIsValidJsonRegardlessOfLocale(@TempDir Path dir) throws IOException {
        // A comma-decimal default locale writes "0,6667", which is not JSON and
        // fails to parse on a machine configured differently from the writer's.
        new ResultStore(dir).save(1, result("single agent"));
        String json = Files.readString(dir.resolve("config-1.json"));
        assertThat(json).contains("\"recall\": 0.6667");
        assertThat(json).doesNotContain(",6667");
    }

    @Test
    @DisplayName("an earlier day's configuration is not lost by a later run")
    void reportSpansConfigurationsFromDifferentRuns(@TempDir Path dir) throws IOException {
        // The real scenario: quota ran out yesterday after configuration 1, and
        // today only 2 completes. The report must contain both, or it silently
        // shrinks to whatever the latest run happened to reach.
        var store = new ResultStore(dir);
        store.save(1, result("single agent, all five areas"));
        store.save(2, result("gate + four agents, no verifier"));
        store.writeReport();

        String report = Files.readString(dir.resolve("RESULTS.md"));
        assertThat(report)
                .contains("single agent, all five areas")
                .contains("gate + four agents, no verifier");
        assertThat(store.recorded()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("a single agent reports no routing score rather than a fake one")
    void routingIsNotAccuracyForAnAgentWithNoRouting(@TempDir Path dir) throws IOException {
        var single = new MeasurementHarness.Result("single agent",
                result("x").outcomes(), false);
        var store = new ResultStore(dir);
        store.save(1, single);
        store.writeReport();

        assertThat(Files.readString(dir.resolve("config-1.json")))
                .contains("\"routingMeaningful\": false");
        // n/a in the table, not 0.00 - a configuration with nothing to route
        // scoring zero would invent a failure.
        assertThat(Files.readString(dir.resolve("RESULTS.md"))).contains("n/a");
    }

    @Test
    @DisplayName("re-saving a configuration replaces it rather than duplicating")
    void savingTwiceOverwrites(@TempDir Path dir) throws IOException {
        var store = new ResultStore(dir);
        store.save(3, result("first attempt"));
        store.save(3, result("re-run after a fix"));

        assertThat(store.recorded()).containsExactly(3);
        assertThat(Files.readString(dir.resolve("config-3.json")))
                .contains("re-run after a fix")
                .doesNotContain("first attempt");
    }

    @Test
    @DisplayName("a report warns when configurations covered different fixture sets")
    void differentFixtureCountsAreFlaggedAsNotComparable(@TempDir Path dir) throws IOException {
        // The normal mid-experiment state: the daily quota is smaller than one
        // configuration, so config 1 finishes over 7 fixtures while config 2 is
        // only 2 in. Recall over 2 fixtures renders identically to recall over
        // 7, and the table looks like a comparison when it is not one.
        var sevenFixtures = new MeasurementHarness.Result("single agent",
                List.of(outcome("F01", false, 1, 1), outcome("F02", false, 1, 1),
                        outcome("F03", false, 1, 1), outcome("F04", false, 1, 1),
                        outcome("F05", false, 1, 1), outcome("F06", false, 1, 1),
                        outcome("F07", true, 0, 0)), false);
        var twoFixtures = new MeasurementHarness.Result("gate + four agents",
                List.of(outcome("F01", false, 1, 1), outcome("F02", false, 1, 0)), true);

        var store = new ResultStore(dir);
        store.save(1, sevenFixtures);
        store.save(2, twoFixtures);
        store.writeReport();

        assertThat(Files.readString(dir.resolve("RESULTS.md")))
                .contains("NOT COMPARABLE YET")
                .contains("7")
                .contains("2");
    }

    @Test
    @DisplayName("no warning when every configuration covered the same fixtures")
    void sameFixtureCountIsNotFlagged(@TempDir Path dir) throws IOException {
        var store = new ResultStore(dir);
        store.save(1, result("single agent"));
        store.save(2, result("gate + four agents"));
        store.writeReport();

        assertThat(Files.readString(dir.resolve("RESULTS.md")))
                .doesNotContain("NOT COMPARABLE");
    }

    private static MeasurementHarness.FixtureOutcome outcome(
            String id, boolean clean, int seeded, int caught) {
        var f = fixture(id, clean, seeded);
        return new MeasurementHarness.FixtureOutcome(f,
                f.expected().subList(0, caught),
                f.expected().subList(caught, seeded),
                f.expected().subList(0, caught),
                List.of(), 0, 0, true, 1000L);
    }

    @Test
    @DisplayName("a report with nothing recorded says so instead of failing")
    void emptyReportIsHonest(@TempDir Path dir) throws IOException {
        new ResultStore(dir).writeReport();
        assertThat(Files.readString(dir.resolve("RESULTS.md")))
                .contains("No configurations recorded yet");
    }

    /**
     * The same three fixtures, but one of them never finished its reviews.
     *
     * <p>Its recall reads exactly like the complete one's - that is the entire
     * hazard, and the reason the three tests below exist.
     */
    private static MeasurementHarness.Result unfinished(String label) {
        var complete = fixture("F01", false, 2);
        var abandoned = fixture("F02", false, 1);

        return new MeasurementHarness.Result(label, List.of(
                new MeasurementHarness.FixtureOutcome(complete,
                        complete.expected(), List.of(), complete.expected(),
                        List.of(), 0, 0, true, 1200L),
                new MeasurementHarness.FixtureOutcome(abandoned,
                        List.of(), abandoned.expected(), List.of(),
                        List.of(), 0, 0, false, 1400L)),
                true);
    }

    @Test
    @DisplayName("a configuration with a reviewer that never ran is quarantined, not reported")
    void anUnfinishedConfigurationIsKeptOutOfTheReport(@TempDir Path dir) throws IOException {
        var store = new ResultStore(dir);
        store.save(3, unfinished("gate + four agents + verifier"));
        store.writeReport();

        assertThat(dir.resolve("config-3.json")).doesNotExist();
        assertThat(dir.resolve("incomplete/config-3.json")).exists();

        // Named in the report rather than silently absent: a discarded run needs
        // re-running and an unattempted one needs starting, and the reader
        // cannot tell those apart from an empty table.
        assertThat(Files.readString(dir.resolve("RESULTS.md")))
                .contains("DISCARDED")
                .contains("config-3.json");
    }

    @Test
    @DisplayName("a clean re-run removes its own earlier quarantined attempt")
    void aSuccessfulRerunSupersedesTheQuarantinedFile(@TempDir Path dir) throws IOException {
        var store = new ResultStore(dir);
        store.save(3, unfinished("gate + four agents + verifier"));
        assertThat(dir.resolve("incomplete/config-3.json")).exists();

        store.save(3, result("gate + four agents + verifier"));
        store.writeReport();

        // Left behind, the stale file would make the report show configuration 3
        // as a result AND announce configuration 3 as discarded - both generated
        // from real files, with nothing to tell the reader which is current.
        assertThat(dir.resolve("config-3.json")).exists();
        assertThat(dir.resolve("incomplete/config-3.json")).doesNotExist();
        assertThat(Files.readString(dir.resolve("RESULTS.md")))
                .doesNotContain("DISCARDED");
    }

    @Test
    @DisplayName("a failed retry of an already-recorded configuration is dropped, not filed")
    void aFailedRetryDoesNotDisplaceAGoodResult(@TempDir Path dir) throws IOException {
        var store = new ResultStore(dir);
        store.save(3, result("gate + four agents + verifier"));
        String recorded = Files.readString(dir.resolve("config-3.json"));

        // VerifierAgent has no cache, so re-running configuration 3 pays for
        // every verifier call again and meets the per-minute token limit every
        // time. This retry is the normal case, not an unlucky one.
        store.save(3, unfinished("gate + four agents + verifier"));
        store.writeReport();

        assertThat(dir.resolve("config-3.json")).content().isEqualTo(recorded);
        assertThat(dir.resolve("incomplete/config-3.json")).doesNotExist();
        assertThat(Files.readString(dir.resolve("RESULTS.md")))
                .doesNotContain("DISCARDED");
    }

    @Test
    @DisplayName("an unwritable directory does not abort the measurement")
    void aFailedWriteIsNotFatal() {
        // A safety net that can kill the run it protects is worse than none.
        // /dev/null/... cannot be created, so this exercises the failure path.
        var store = new ResultStore(Path.of("/dev/null/measurements"));
        store.save(1, result("single agent"));   // must not throw
        store.writeReport();                     // must not throw
        assertThat(store.recorded()).isEmpty();
    }
}
