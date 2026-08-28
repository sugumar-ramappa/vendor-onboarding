package com.learning.onboarding.measure;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.agents.ReviewOutcome;
import com.learning.onboarding.agents.ReviewerAgent;
import com.learning.onboarding.domain.AuditEntry;
import com.learning.onboarding.domain.ReviewFinding;
import com.learning.onboarding.domain.Severity;
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
        return run(configurationLabel, graph, fixtures, true);
    }

    /**
     * @param routingMeaningful false when one agent covers every area
     */
    public Result run(String configurationLabel, ReviewGraph graph, List<Fixture> fixtures,
                      boolean routingMeaningful) {
        List<FixtureOutcome> outcomes = new ArrayList<>();

        for (Fixture fixture : fixtures) {
            ReviewContext context = loader.toContext(fixture);
            long started = System.currentTimeMillis();
            ReviewState state = graph.review(context);
            long wallClockMs = System.currentTimeMillis() - started;

            // Surviving findings only. A finding the verifier refuted was not
            // shown to anyone, so counting it would measure what the system
            // thought before it checked itself.
            List<ReviewFinding> reported = state.survivingFindings();

            // A fixture counts as complete only if every reviewer ran AND every
            // finding was actually challenged. The second half was missing, so a
            // configuration whose verifier failed on every finding still recorded
            // a clean result - see ReviewState.verifierFailures().
            boolean complete = state.allReviewersRan() && state.verifierFailures() == 0;

            FixtureOutcome outcome = score(fixture, reported, state.conflicts().size(),
                    state.discarded().size(), complete, wallClockMs,
                    criticalPathMs(state.gateAudit(), state.reviewerAudit()));
            outcomes.add(outcome);

            log.info("{} / {}: {} caught, {} missed, {} unexpected",
                    configurationLabel, fixture.id(), outcome.caught().size(),
                    outcome.missed().size(), outcome.unexpected().size());
        }
        return new Result(configurationLabel, outcomes, routingMeaningful);
    }

    /**
     * The baseline: one model call per fixture, no graph.
     *
     * <p>The graph is the thing being tested, so the control must not use it.
     * Running the baseline through {@code ReviewGraph} meant a gate node plus a
     * reviewer node - two calls with the same prompt, and two independent
     * chances to spot a defect. That is not "one prompt does everything", it is
     * the multi-agent design with worse prompts, and it flatters the baseline on
     * recall while doubling the cost attributed to it.
     *
     * <p>No conflict detection and no grounding pass, which matches
     * configuration 2: with no verifier the graph's verify node returns
     * immediately, so neither configuration grounds its citations. The
     * comparison stays like-for-like.
     */
    public Result runSingleAgent(String configurationLabel, ReviewerAgent agent,
                                 List<Fixture> fixtures) {
        List<FixtureOutcome> outcomes = new ArrayList<>();

        for (Fixture fixture : fixtures) {
            ReviewContext context = loader.toContext(fixture);
            long started = System.currentTimeMillis();
            ReviewOutcome outcome = agent.review(context);
            long wallClockMs = System.currentTimeMillis() - started;

            // A reviewer that could not run is not a reviewer that found
            // nothing - the same distinction the graph makes, kept here so a
            // rate-limited baseline cannot look like a clean one.
            List<ReviewFinding> reported =
                    outcome.succeeded() ? outcome.findings() : List.of();

            // One call, so the critical path is that call. Taken from the audit
            // entry rather than the stopwatch for the same reason as the graph
            // path: on a cached run the stopwatch reports the lookup, and the
            // audit entry reports what the call originally cost.
            outcomes.add(score(fixture, reported, 0, 0, outcome.succeeded(), wallClockMs,
                    outcome.audit().latencyMs()));

            log.info("{} / {}: {} finding(s){}", configurationLabel, fixture.id(),
                    reported.size(), outcome.succeeded() ? "" : " - REVIEWER FAILED");
        }
        // One agent covering every area has no routing to be right about.
        return new Result(configurationLabel, outcomes, false);
    }

    /**
     * Latency along the critical path, rebuilt from the per-call audit records.
     *
     * <h2>Why this exists beside wall clock</h2>
     *
     * Wall clock is the honest number for a fresh run and a meaningless one for a
     * cached re-run: the 2026-08-28 configurations 1 and 2 were last regenerated
     * from cache and recorded 8 ms and 22 ms. The 28 s and 88 s those runs
     * originally took survived only in prose, which is not a place a measurement
     * should live.
     *
     * <p>They were recoverable because {@code ReviewerAgent} returns the cache
     * hit's {@code originalLatencyMs} in its audit entry rather than the
     * microseconds the lookup took. Every real call's duration is therefore still
     * in the run, whether or not the model was asked again.
     *
     * <h2>The path</h2>
     *
     * The gate runs to completion before the fan-out, and the four reviewers then
     * run concurrently on virtual threads. So the reconstruction is
     * {@code gate + max(reviewers)} - not the sum, which would report
     * configuration 2 as five times slower than the baseline when it is roughly
     * three.
     *
     * <p><b>What it does not include: the verifier.</b> {@code VerifierAgent}
     * records no {@link AuditEntry}, so configuration 3's challenge calls are
     * invisible here and its number is a floor rather than a total. That is the
     * same blind spot that let the most expensive component go uncached, and it
     * is recorded rather than papered over.
     */
    static long criticalPathMs(List<AuditEntry> gate, List<AuditEntry> reviewers) {
        long gateMs = gate.stream()
                .mapToLong(AuditEntry::latencyMs)
                .sum();
        long slowestReviewer = reviewers.stream()
                .mapToLong(AuditEntry::latencyMs)
                .max()
                .orElse(0L);
        return gateMs + slowestReviewer;
    }

    /** Scores one fixture's reported findings against its planted defects. */
    private FixtureOutcome score(Fixture fixture, List<ReviewFinding> reported,
                                 int conflicts, int discarded, boolean complete,
                                 long wallClockMs, long criticalPathMs) {
        List<Fixture.ExpectedDefect> caught = new ArrayList<>();
        List<Fixture.ExpectedDefect> missed = new ArrayList<>();
        List<Fixture.ExpectedDefect> routed = new ArrayList<>();

        for (Fixture.ExpectedDefect defect : fixture.expected()) {
            if (reported.stream().anyMatch(defect::matchedBy)) {
                caught.add(defect);
                if (reported.stream().anyMatch(defect::routedCorrectly)) {
                    routed.add(defect);
                }
            } else {
                missed.add(defect);
            }
        }

        List<ReviewFinding> unexpected = reported.stream()
                .filter(f -> fixture.expected().stream().noneMatch(d -> d.matchedBy(f)))
                .toList();

        return new FixtureOutcome(fixture, caught, missed, routed, unexpected,
                conflicts, discarded, complete, wallClockMs, criticalPathMs);
    }

    /** What happened to one fixture. */
    /**
     * @param wallClockMs how long this fixture took end to end.
     *
     * <p>Wall clock, deliberately, not the sum of the model calls. Configuration 2
     * makes five calls but runs four of them concurrently, so summing would report
     * it as five times slower than the baseline when it is not. Cost and latency do
     * not scale together here and the whole point of recording this is to show
     * where they diverge.
     *
     * <p><b>Only comparable within one uncached, unthrottled run.</b> A cached call
     * returns in about a millisecond and a rate-limited one spends 20 seconds in
     * backoff, so either will dominate this number completely. Use
     * {@code criticalPathMs} for a figure that survives a cached re-run.
     *
     * @param criticalPathMs the same journey rebuilt from the audit records:
     *                       gate plus the slowest concurrent reviewer. Because a
     *                       cache hit reports the original call's duration, this
     *                       stays meaningful when {@code wallClockMs} does not.
     *                       Excludes the verifier, which writes no audit entry.
     */
    public record FixtureOutcome(
            Fixture fixture,
            List<Fixture.ExpectedDefect> caught,
            List<Fixture.ExpectedDefect> missed,
            List<Fixture.ExpectedDefect> routed,
            List<ReviewFinding> unexpected,
            int conflicts,
            int discardedUngrounded,
            boolean complete,
            long wallClockMs,
            long criticalPathMs
    ) {}

    /**
     * One configuration's results across every fixture.
     *
     * @param routingMeaningful false for a single agent covering every area -
     *                          it has no routing to get right, so reporting a
     *                          routing score for it would invent a failure
     */
    public record Result(String configuration, List<FixtureOutcome> outcomes,
                         boolean routingMeaningful) {

        public int seeded() {
            return outcomes.stream().mapToInt(o -> o.fixture().expected().size()).sum();
        }

        public int caught() {
            return outcomes.stream().mapToInt(o -> o.caught().size()).sum();
        }

        /**
         * Detected defects over planted defects, regardless of which agent
         * found it. The number that is comparable across configurations.
         */
        public double recall() {
            return seeded() == 0 ? 0 : (double) caught() / seeded();
        }

        public int routed() {
            return outcomes.stream().mapToInt(o -> o.routed().size()).sum();
        }

        /**
         * Of the defects that were detected, how many reached the reviewer whose
         * area they belong to.
         *
         * <p>Denominator is caught, not seeded: this asks whether detection went
         * to the right place, not how much was detected. Mixing the two would
         * make a configuration that finds less look better at routing.
         */
        public double routingAccuracy() {
            return caught() == 0 ? 0 : (double) routed() / caught();
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

        /**
         * The subset of {@link #falsePositives()} that would actually stop a
         * vendor: {@code MAJOR} or {@code BLOCKING}.
         *
         * <h2>Why this number exists beside the other one</h2>
         *
         * The 2026-08-28 run recorded 31 false positives on two clean packs,
         * which reads as a system that cries wolf. Reading the findings said
         * otherwise: they are overwhelmingly {@code INFO} entries of the form
         * <i>"product liability insurance meets the minimum required GBP 5M"</i>
         * and <i>"case weight of 9.8 kg is below the manual handling limit of
         * 25 kg"</i>.
         *
         * <p>Those are not accusations. They are the reviewer stating what it
         * checked and that the pack passed - an audit trail, and arguably the
         * most useful thing it produces. {@code falsePositives()} counts them
         * identically to a {@code BLOCKING} claim that a compliant vendor is
         * uninsured, and those two things do not cost the same.
         *
         * <p><b>Both are reported, and neither replaces the other.</b> Narrowing
         * a metric after seeing a number it made look bad is exactly the move
         * {@code PREDICTIONS.md} exists to prevent, so the original figure stays
         * in the results file unchanged and this is added next to it. The reader
         * gets to see that the split was made and what it does to the number.
         *
         * <p>The deeper problem is upstream and is not fixed by counting
         * differently: {@code ReviewOutput} gives a reviewer nowhere to say
         * "I checked this and it is fine", so a pass has to be expressed as a
         * finding. The model is using the only channel it has.
         */
        public int actionableFalsePositives() {
            return outcomes.stream()
                    .filter(o -> o.fixture().clean())
                    .mapToInt(o -> (int) o.unexpected().stream()
                            .filter(f -> f.severity() != null
                                    && f.severity().compareTo(Severity.MAJOR) >= 0)
                            .count())
                    .sum();
        }

        /** Clean-pack findings that only confirm the pack is compliant. */
        public int confirmatoryFindings() {
            return falsePositives() - actionableFalsePositives();
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
            String routing = routingMeaningful
                    ? "routing %.2f (%d/%d caught reached the right reviewer)"
                            .formatted(routingAccuracy(), routed(), caught())
                    : "routing n/a (one agent covers every area)";

            return """
                    %-34s  recall %.2f (%d/%d)   false positives %.2f (%d over %d clean)
                      %s
                      %d finding(s) need human review, %d discarded as ungrounded, %d conflict(s)%s"""
                    .formatted(configuration, recall(), caught(), seeded(),
                            falsePositiveRate(), falsePositives(), cleanFixtures(),
                            routing,
                            needsReview(), ungroundedDiscarded(), conflictsFound(),
                            incompleteRuns() == 0 ? ""
                                    : "%n  WARNING: %d fixture(s) had a reviewer that did not run - "
                                      .formatted(incompleteRuns())
                                      + "these numbers measure the free tier, not the architecture");
        }
    }
}
