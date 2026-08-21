package com.learning.onboarding.measure;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.domain.ReviewFinding;
import com.learning.onboarding.graph.ReviewGraph;
import com.learning.onboarding.graph.ReviewState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the fixtures through a configuration and reports what it caught.
 *
 * <p>The point of the whole project. "Multi-agent is better" is an assertion
 * until four configurations have been run over the same applications and the
 * numbers compared - and the comparison has to report two metrics, because
 * either one alone can be gamed.
 *
 * <pre>
 *                                       recall   false-positive
 *   single agent, all five areas           ?           ?
 *   five agents, no verifier               ?           ?
 *   five agents + verifier                 ?           ?
 *   five agents + verifier + conflicts     ?           ?
 * </pre>
 *
 * <h2>Why two metrics</h2>
 * A system that reports every application as BLOCKING has perfect recall and is
 * useless. One that reports nothing has a perfect false-positive rate and is
 * equally useless. Reported together they constrain each other; reported alone
 * either is meaningless.
 *
 * <h2>Unexpected findings are reported, not counted as wrong</h2>
 * A finding on a defective fixture that matches no planted defect might be a
 * false positive, or it might be a real problem the fixture author missed. The
 * honest thing is to surface it for a human to judge rather than silently score
 * it either way. Only findings on CLEAN fixtures are counted as false positives,
 * because there the correct answer is known to be nothing.
 */
public class MeasurementHarness {

    private static final Logger log = LoggerFactory.getLogger(MeasurementHarness.class);

    private final FixtureLoader loader;

    public MeasurementHarness(FixtureLoader loader) {
        this.loader = loader;
    }

    public Result run(String configurationLabel, ReviewGraph graph, List<Fixture> fixtures) {
        List<FixtureOutcome> outcomes = new ArrayList<>();

        for (Fixture fixture : fixtures) {
            ReviewContext context = loader.toContext(fixture);
            ReviewState state = graph.review(context);

            // Surviving findings only. A finding the verifier refuted was not
            // shown to anyone, so counting it would measure what the system
            // thought before it checked itself.
            List<ReviewFinding> reported = state.survivingFindings();

            List<Fixture.ExpectedDefect> caught = new ArrayList<>();
            List<Fixture.ExpectedDefect> missed = new ArrayList<>();

            for (Fixture.ExpectedDefect defect : fixture.expected()) {
                if (reported.stream().anyMatch(defect::matchedBy)) {
                    caught.add(defect);
                } else {
                    missed.add(defect);
                }
            }

            List<ReviewFinding> unexpected = reported.stream()
                    .filter(f -> fixture.expected().stream().noneMatch(d -> d.matchedBy(f)))
                    .toList();

            outcomes.add(new FixtureOutcome(fixture, caught, missed, unexpected,
                    state.conflicts().size(), state.discarded().size(),
                    state.allReviewersRan()));

            log.info("{} / {}: {} caught, {} missed, {} unexpected",
                    configurationLabel, fixture.id(), caught.size(), missed.size(),
                    unexpected.size());
        }
        return new Result(configurationLabel, outcomes);
    }

    /** What happened to one fixture. */
    public record FixtureOutcome(
            Fixture fixture,
            List<Fixture.ExpectedDefect> caught,
            List<Fixture.ExpectedDefect> missed,
            List<ReviewFinding> unexpected,
            int conflicts,
            int discardedUngrounded,
            boolean complete
    ) {}

    /** One configuration's results across every fixture. */
    public record Result(String configuration, List<FixtureOutcome> outcomes) {

        public int seeded() {
            return outcomes.stream().mapToInt(o -> o.fixture().expected().size()).sum();
        }

        public int caught() {
            return outcomes.stream().mapToInt(o -> o.caught().size()).sum();
        }

        /** Detected defects over planted defects. */
        public double recall() {
            return seeded() == 0 ? 0 : (double) caught() / seeded();
        }

        /**
         * Findings raised on applications where the correct answer was nothing.
         *
         * <p>Only clean fixtures count. On a defective one, a finding that
         * matches no planted defect might be a real problem nobody thought to
         * plant, and scoring it as wrong would punish the system for being
         * better than the fixture author.
         */
        public int falsePositives() {
            return outcomes.stream()
                    .filter(o -> o.fixture().clean())
                    .mapToInt(o -> o.unexpected().size())
                    .sum();
        }

        public int cleanFixtures() {
            return (int) outcomes.stream().filter(o -> o.fixture().clean()).count();
        }

        public double falsePositiveRate() {
            return cleanFixtures() == 0 ? 0 : (double) falsePositives() / cleanFixtures();
        }

        /**
         * Findings on defective fixtures that matched nothing planted.
         *
         * <p>Reported separately and deliberately not scored: these need a human
         * to say whether they are noise or something the fixture missed.
         */
        public int needsReview() {
            return outcomes.stream()
                    .filter(o -> !o.fixture().clean())
                    .mapToInt(o -> o.unexpected().size())
                    .sum();
        }

        public int ungroundedDiscarded() {
            return outcomes.stream().mapToInt(FixtureOutcome::discardedUngrounded).sum();
        }

        public int conflictsFound() {
            return outcomes.stream().mapToInt(FixtureOutcome::conflicts).sum();
        }

        /**
         * Fixtures where some reviewer did not run.
         *
         * <p>Reported prominently: a run with incomplete reviews produces
         * numbers that look like results and are not. Quoting recall from a run
         * where two reviewers were rate-limited would be reporting a
         * measurement of the free tier.
         */
        public int incompleteRuns() {
            return (int) outcomes.stream().filter(o -> !o.complete()).count();
        }

        public String summary() {
            return """
                    %-34s  recall %.2f (%d/%d)   false positives %.2f (%d over %d clean)
                      %d finding(s) need human review, %d discarded as ungrounded, %d conflict(s)%s"""
                    .formatted(configuration, recall(), caught(), seeded(),
                            falsePositiveRate(), falsePositives(), cleanFixtures(),
                            needsReview(), ungroundedDiscarded(), conflictsFound(),
                            incompleteRuns() == 0 ? ""
                                    : "%n  WARNING: %d fixture(s) had a reviewer that did not run - "
                                      .formatted(incompleteRuns())
                                      + "these numbers measure the free tier, not the architecture");
        }
    }
}
