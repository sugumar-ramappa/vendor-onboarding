package com.learning.onboarding.measure;

import com.learning.onboarding.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fixtures themselves, and the rule that decides whether a finding caught a
 * planted defect.
 *
 * <p>Worth testing carefully. A matcher that is too strict reports every prompt
 * edit as a regression, because the model phrased the same finding differently.
 * One that is too loose credits the system for findings it did not make, and
 * the measurement becomes flattering rather than useful.
 */
class FixtureLoaderTest {

    private final FixtureLoader loader = new FixtureLoader("fixtures");

    private static ReviewFinding finding(ReviewArea area, Severity severity,
                                         String problem, String sku) {
        var details = new AgentFinding(severity, problem,
                List.of(new Evidence("cert.pdf", 1, "some quoted text")),
                CheckType.SEMANTIC, 0.9, sku);

        return ReviewFinding.unverified(UUID.randomUUID().toString(), area, details,
                new FindingSource(UUID.randomUUID(), "v2", "stub", Instant.now()));
    }

    @Test
    @DisplayName("every fixture on disk parses")
    void allFixturesParse() {
        var fixtures = loader.loadAll();

        assertFalse(fixtures.isEmpty());
        fixtures.forEach(f -> {
            assertNotNull(f.id());
            assertFalse(f.description().isBlank(), f.id() + " has no description");
            assertNotNull(f.applicationJson());
        });
    }

    @Test
    @DisplayName("there is at least one clean fixture")
    void hasCleanFixtures() {
        assertTrue(loader.loadAll().stream().anyMatch(Fixture::clean),
                "without an application whose correct answer is 'no findings', the "
                        + "false-positive rate cannot be measured - and recall alone "
                        + "is gamed by flagging everything");
    }

    @Test
    @DisplayName("a clean fixture plants no defects")
    void cleanFixturesHaveNoExpectedDefects() {
        loader.loadAll().stream().filter(Fixture::clean).forEach(f ->
                assertTrue(f.expected().isEmpty(),
                        f.id() + " is marked clean but plants defects"));
    }

    @Test
    @DisplayName("a defective fixture plants at least one")
    void defectiveFixturesHaveExpectedDefects() {
        loader.loadAll().stream().filter(f -> !f.clean()).forEach(f ->
                assertFalse(f.expected().isEmpty(),
                        f.id() + " is not marked clean but plants nothing to find"));
    }

    @Test
    @DisplayName("a fixture becomes the same context the real pipeline builds")
    void fixtureBecomesAContext() {
        var fixture = loader.loadAll().stream()
                .filter(f -> f.id().equals("F01")).findFirst().orElseThrow();

        var context = loader.toContext(fixture);

        assertEquals("F01-APP", context.applicationId());
        assertFalse(context.documents().isEmpty());
        // The same deterministic extraction the real pipeline runs, so the
        // measurement exercises the actual code path.
        assertFalse(context.facts().isEmpty(),
                "facts must be extracted, or the measurement tests a shortcut");
        assertTrue(context.render().contains("<untrusted"),
                "fixture documents must be spotlighted exactly like real ones");
    }

    // ------------------------------------------------------------ matching --

    @Test
    @DisplayName("wording differences do not fail a genuine catch")
    void matcherToleratesPhrasing() {
        var defect = new Fixture.ExpectedDefect(ReviewArea.COMPLIANCE, Severity.MAJOR,
                "ACM-DRL-18V", List.of("scope"), "note");

        assertTrue(defect.matchedBy(finding(ReviewArea.COMPLIANCE, Severity.BLOCKING,
                "The scope of certification does not cover this SKU", "ACM-DRL-18V")));

        assertTrue(defect.matchedBy(finding(ReviewArea.COMPLIANCE, Severity.MAJOR,
                "SKU is outside the certificate's stated scope", "ACM-DRL-18V")),
                "the same defect phrased differently must still count, or every "
                        + "prompt edit looks like a regression");
    }

    @Test
    @DisplayName("a more serious report still counts")
    void moreSevereStillCounts() {
        var defect = new Fixture.ExpectedDefect(ReviewArea.COMPLIANCE, Severity.MAJOR,
                null, List.of("scope"), "note");

        assertTrue(defect.matchedBy(finding(ReviewArea.COMPLIANCE, Severity.BLOCKING,
                "scope problem", null)));
    }

    @Test
    @DisplayName("a less serious report does not")
    void lessSevereDoesNotCount() {
        var defect = new Fixture.ExpectedDefect(ReviewArea.COMPLIANCE, Severity.MAJOR,
                null, List.of("scope"), "note");

        assertFalse(defect.matchedBy(finding(ReviewArea.COMPLIANCE, Severity.INFO,
                "minor scope note", null)),
                "reporting a blocking problem as INFO is not catching it - nobody "
                        + "acts on an INFO finding");
    }

    @Test
    @DisplayName("the right problem from the wrong reviewer does not count")
    void wrongAreaDoesNotCount() {
        var defect = new Fixture.ExpectedDefect(ReviewArea.COMPLIANCE, Severity.MAJOR,
                null, List.of("scope"), "note");

        assertFalse(defect.matchedBy(finding(ReviewArea.QUALITY, Severity.BLOCKING,
                "scope problem", null)),
                "which reviewer caught it is part of what is being measured");
    }

    @Test
    @DisplayName("the right problem about the wrong SKU does not count")
    void wrongSkuDoesNotCount() {
        var defect = new Fixture.ExpectedDefect(ReviewArea.COMPLIANCE, Severity.MAJOR,
                "ACM-DRL-18V", List.of("scope"), "note");

        assertFalse(defect.matchedBy(finding(ReviewArea.COMPLIANCE, Severity.BLOCKING,
                "scope problem", "ACM-HAM-16")));
    }
}
