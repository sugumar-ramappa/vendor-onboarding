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
                        List.of(), 0, 0, true),
                new MeasurementHarness.FixtureOutcome(alsoDefective,
                        List.of(), alsoDefective.expected(), List.of(),
                        List.of(), 1, 0, true),
                new MeasurementHarness.FixtureOutcome(clean,
                        List.of(), List.of(), List.of(), List.of(), 0, 1, true)),
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
    @DisplayName("a report with nothing recorded says so instead of failing")
    void emptyReportIsHonest(@TempDir Path dir) throws IOException {
        new ResultStore(dir).writeReport();
        assertThat(Files.readString(dir.resolve("RESULTS.md")))
                .contains("No configurations recorded yet");
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
