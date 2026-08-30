package com.learning.onboarding.measure;

import com.learning.onboarding.agents.*;
import com.learning.onboarding.config.PolicyProperties;
import com.learning.onboarding.domain.EvidenceNeed;
import com.learning.onboarding.domain.ReviewArea;
import com.learning.onboarding.graph.ConflictDetector;
import com.learning.onboarding.graph.EvidenceGatherer;
import com.learning.onboarding.graph.GroundingCheck;
import com.learning.onboarding.graph.ReviewGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs the measurement.
 *
 * <pre>
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=measure
 * </pre>
 *
 * <p>Three configurations over the same fixtures, so each difference has one
 * cause. Adding the verifier and changing a prompt in the same run would give
 * one number and no attribution - the rule carried over from the retrieval
 * project in this workspace, and the reason it is worth following is that a
 * result you cannot attribute is a result you cannot defend.
 *
 * <h2>The order is chosen to make the experiment affordable</h2>
 * <pre>
 *   1. single agent          14 fixtures  =  14 model calls
 *   2. five agents           14 x 5       =  70 model calls
 *   3. five agents + verify  reviewers served from cache, so only the
 *                            verifier calls are new   ~30 calls
 * </pre>
 *
 * <p>Configuration 3 reuses configuration 2's reviewer output because the cache
 * key is (area, prompt version, model, prompt) and none of those changed -
 * adding a verifier does not alter what the reviewers were asked. Running them
 * the other way round would double the cost for the same numbers.
 *
 * <p>Roughly 115 calls in total, which fits inside a day of the free tier. That
 * is not incidental: an experiment that cannot be repeated within a day will be
 * run once, and an experiment run once is an anecdote.
 */
@Component
@Profile("measure")
public class MeasurementRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MeasurementRunner.class);

    private final FixtureLoader loader;
    private final PromptLibrary prompts;
    private final ReviewModel reviewModel;
    private final ReviewCache cache;
    private final VerifierAgent verifier;
    private final GroundingCheck grounding;
    private final ConflictDetector conflicts;
    private final PolicyProperties policy;
    private final EvidenceGatherer gatherer;

    public MeasurementRunner(FixtureLoader loader, PromptLibrary prompts,
                             ReviewModel reviewModel, ReviewCache cache,
                             VerifierAgent verifier, GroundingCheck grounding,
                             ConflictDetector conflicts, PolicyProperties policy,
                             EvidenceGatherer gatherer) {
        this.policy = policy;
        this.gatherer = gatherer;
        this.loader = loader;
        this.prompts = prompts;
        this.reviewModel = reviewModel;
        this.cache = cache;
        // Scoped to the model that will actually answer, so a run on a second
        // provider cannot overwrite the first provider's results with a file of
        // the same name. Taken from the model itself rather than a property, so
        // it cannot disagree with what served the calls.
        this.store = ResultStore.forModel(reviewModel.modelName());
        this.verifier = verifier;
        this.grounding = grounding;
        this.conflicts = conflicts;
    }

    /**
     * Which configurations to run, from {@code -Dmeasure.configs=1,2}.
     *
     * <p>Defaults to all three.
     */
    @Value("${measure.configs:1,2,3}")
    private String configsToRun;

    /**
     * Which fixtures to run, from {@code -Dmeasure.fixtures=F01,F03,F02}.
     *
     * <p>Empty means all of them. Exists because the free tier allows 20 model
     * requests per day and the full experiment is around 224, so the realistic
     * choice is not "when do I run this" but "which subset can I afford".
     *
     * <p>Choose a subset that keeps every review area represented and keeps the
     * clean fixtures. Dropping a clean fixture removes the only place false
     * positives are counted at all, which is the number the verifier exists to
     * move.
     */
    @Value("${measure.fixtures:F01,F03,F06,F08,F10,F02,F12}")
    private String fixturesToRun;

    /**
     * Pre-resolve each reviewer's rulebook into its prompt instead of exposing
     * MCP tools.
     *
     * <p>Halves the experiment. A tool call is a SECOND API request - the model
     * pauses, the tool runs locally, and the result goes back in a new request -
     * and four of the five reviewers call one. The free tier allows 20 requests
     * a day, so this is the difference between three days and six.
     *
     * <p>Same SQL, same rows, same reviewer prompts. What changes is only how
     * the data arrives. Both configurations get it identically, so the
     * comparison - does specialisation help? - is unaffected, and it removes
     * tool-calling reliability as a confound in an experiment that is not about
     * function calling.
     *
     * <p>Set {@code onboarding.model.tools-enabled=false} alongside it, or the
     * model may fetch what it has already been given.
     */
    @Value("${measure.prefetch-rules:true}")
    private boolean prefetchRules;

    /**
     * Writes each configuration to measurements/ as it finishes.
     *
     * <p>The cache protects quota; this protects the result. Two days of
     * measurement were lost when the Postgres container was removed, because
     * the only record of the experiment was inside it.
     */
    private final ResultStore store;

    @Override
    public void run(String... args) throws Exception {
        List<Fixture> fixtures = loader.loadAll();

        if (!fixturesToRun.isBlank()) {
            Set<String> wanted = Arrays.stream(fixturesToRun.split(","))
                    .map(String::trim).filter(t -> !t.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            List<Fixture> chosen = fixtures.stream()
                    .filter(f -> wanted.contains(f.id())).toList();

            // A typo in a fixture id would otherwise silently shrink the
            // experiment, and a recall figure over four fixtures looks exactly
            // like a recall figure over fourteen.
            if (chosen.size() != wanted.size()) {
                Set<String> found = chosen.stream().map(Fixture::id)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                throw new IllegalArgumentException("unknown fixture id(s): "
                        + wanted.stream().filter(w -> !found.contains(w)).toList());
            }
            fixtures = chosen;
        }
        final List<Fixture> selectedFixtures = fixtures;

        var harness = new MeasurementHarness(loader);
        var results = new ArrayList<MeasurementHarness.Result>();
        var completed = new LinkedHashSet<Integer>();

        Set<Integer> selected = Arrays.stream(configsToRun.split(","))
                .map(String::trim).filter(t -> !t.isEmpty())
                .map(Integer::parseInt).collect(Collectors.toCollection(LinkedHashSet::new));

        System.out.printf("%n=== measurement: %d fixtures, %d planted defects, %d clean ===%n",
                selectedFixtures.size(),
                selectedFixtures.stream().mapToInt(f -> f.expected().size()).sum(),
                selectedFixtures.stream().filter(Fixture::clean).count());
        System.out.printf("=== running configuration(s) %s ===%n", selected);
        printCostEstimate(selectedFixtures.size(), selected);

        // 1. One reviewer covering all five areas. The baseline the whole
        //    project exists to beat - or to fail to beat, which would be a more
        //    interesting result and worth reporting either way.
        //
        //    It is its own completeness gate, so the gate always passes and
        //    every fixture reaches it. That is the honest comparison: the
        //    baseline is "one prompt does everything", not "one prompt behind
        //    our routing".
        if (selected.contains(1)) {
            runNamed(results, completed, 1, "single agent, all five areas",
                    () -> harness.runSingleAgent("single agent, all five areas",
                            reviewer(ReviewArea.COMPLIANCE, "single-v1"), selectedFixtures));
        }

        // 2. Completeness gate plus four isolated reviewers, no verification.
        if (selected.contains(2)) {
            runNamed(results, completed, 2, "gate + four agents, no verifier",
                    () -> harness.run("gate + four agents, no verifier",
                            graph(completenessGate(), fourReviewers(), null), selectedFixtures));
        }

        // 3. The same, now challenged. The reviewers' output comes from cache,
        //    so the only new cost is the verifier itself.
        if (selected.contains(3)) {
            runNamed(results, completed, 3, "gate + four agents + verifier",
                    () -> harness.run("gate + four agents + verifier",
                            graph(completenessGate(), fourReviewers(), verifier), selectedFixtures));
        }

        // 4. The cheap architecture with the expensive guardrail bolted on.
        //
        //    Predicted to lose, and run because it explains why the winner wins.
        //    The verifier can only REMOVE findings, so recall is capped at
        //    configuration 1's 10/14 - it can clean up the six fabrications the
        //    single agent produced and it cannot recover the four defects the
        //    single agent never looked for.
        //
        //    No gate, deliberately. The single agent is its own everything, the
        //    same as in configuration 1, and giving it a gate here would
        //    short-circuit on its own BLOCKING fabrications before the verifier
        //    ever ran. Same agent, same prompt version, so its findings come
        //    from cache and only the verifier calls are new.
        if (selected.contains(4)) {
            runNamed(results, completed, 4, "single agent + verifier",
                    () -> harness.run("single agent + verifier",
                            graph(null, List.of(reviewer(ReviewArea.COMPLIANCE, "single-v1")),
                                    verifier),
                            selectedFixtures, false));
        }

        System.out.printf("%n=== results ===%n%n");
        results.forEach(r -> System.out.println("  " + r.summary() + "\n"));

        // Which ones are still owed, by number - not "the last N". A quota
        // failure on configuration 1 with 2 and 3 succeeding is unlikely but
        // arithmetic that only works when failures come last is arithmetic that
        // will be wrong exactly once, silently, on the day it matters.
        var unfinished = selected.stream().filter(c -> !completed.contains(c)).toList();

        if (!unfinished.isEmpty()) {
            System.out.printf("""
                      %d of %d configuration(s) did not finish.

                      Every model call that DID succeed is in the review_cache table,
                      which lives in Postgres and survives this process. Re-running the
                      unfinished configurations tomorrow re-uses all of it, so the second
                      run costs only what the first one never reached:

                        ./mvnw spring-boot:run -Dspring-boot.run.profiles=measure \\
                          -Dspring-boot.run.jvmArguments="-Dmeasure.configs=%s"
                    %n""", unfinished.size(), selected.size(),
                    unfinished.stream().map(String::valueOf).collect(Collectors.joining(",")));
        }

        System.out.printf("  results written to measurements/ (configurations recorded: %s)%n%n",
                store.recorded());

        System.out.println("""
                  Recall and false positives are reported together on purpose.
                  A system that flags everything has perfect recall and is useless;
                  one that flags nothing has a perfect false-positive rate and is
                  equally useless. Either number alone can be gamed.
                """);
    }

    /**
     * Runs one configuration and prints it immediately.
     *
     * <p>Printed as it completes, not collected and printed at the end. The
     * daily free-tier quota is roughly the size of this experiment, so running
     * out partway through is the expected case rather than an unlucky one - and
     * holding the first two results until the third finishes throws away the
     * work that did succeed.
     *
     * <p>The failure is caught rather than propagated for the same reason. A
     * quota error on configuration 3 must not take configuration 2's numbers
     * with it.
     */
    /**
     * What this run will cost, before it costs it.
     *
     * <p>The free tier allows 20 requests per day per model. Discovering that a
     * run needs eleven days by watching it fail on day one is an expensive way
     * to find out, and the numbers are known in advance.
     *
     * <p>A tool call is a second request, not a free extra: the model pauses,
     * the tool runs locally, and the result goes back in a new request. Four of
     * the five reviewers call a tool.
     */
    private void printCostEstimate(int fixtureCount, Set<Integer> selected) {
        // With tools, every reviewer that calls one costs a second request.
        // With the rulebook pre-resolved, each reviewer is a single request.
        int perFixture = prefetchRules
                ? (selected.contains(1) ? 1 : 0)
                        + (selected.contains(2) ? 5 : 0)
                        + (selected.contains(3) ? 2 : 0)
                : (selected.contains(1) ? 2 : 0)
                        + (selected.contains(2) ? 9 : 0)
                        + (selected.contains(3) ? 5 : 0);
        int total = perFixture * fixtureCount;

        System.out.printf("""
                === estimated cost ===
                  %d fixture(s) x ~%d request(s) = ~%d model request(s)   [rules %s]
                  free tier is %d/day per model, so this needs ~%d day(s)
                  cached calls from earlier runs do not count - the real number
                  will be lower if you have run this configuration before
                %n""", fixtureCount, perFixture, total,
                prefetchRules ? "pre-resolved" : "via MCP tools", FREE_TIER_DAILY,
                Math.max(1, (int) Math.ceil((double) total / FREE_TIER_DAILY)));
    }

    /** Observed on 2026-08-22, from the quota id in a 429 response. */
    private static final int FREE_TIER_DAILY = 20;

    private void runNamed(List<MeasurementHarness.Result> results, Set<Integer> completed,
                          int number, String label, ResultSupplier run) {
        System.out.printf("--- configuration %d: %s ---%n", number, label);
        try {
            MeasurementHarness.Result result = run.get();
            results.add(result);
            completed.add(number);
            // Flushed here, not after the loop. Running out of quota partway
            // through is the expected case, and a configuration that finished
            // is worth keeping even if the next one dies mid-call.
            store.save(number, result);
            store.writeReport();
            System.out.printf("%n  %s%n%n", result.summary());

        } catch (Exception e) {
            System.out.printf("%n  configuration %d did not finish: %s%n%n",
                    number, e.getMessage());
            log.warn("configuration {} failed", number, e);
        }
    }

    @FunctionalInterface
    private interface ResultSupplier {
        MeasurementHarness.Result get() throws Exception;
    }

    private ReviewerAgent completenessGate() {
        return reviewer(ReviewArea.COMPLETENESS, "completeness-v3");
    }

    private List<ReviewerAgent> fourReviewers() {
        return List.of(
                // All four on v3 now, not one at a time. Yesterday logistics-v3
                // was written and the other three deferred with a note saying
                // "has not failed yet is not the same as is correct" - and
                // compliance failed the next day with the identical
                // tool_choice error, costing the run it was meant to save.
                reviewer(ReviewArea.COMPLIANCE, "compliance-v3"),
                reviewer(ReviewArea.QUALITY, "quality-v2"),
                // v3, not v2. v2 instructs the model to fetch its rulebook from
                // tools, and the measurement disables tools because a tool call
                // is a second billed request. Gemini responded to that by
                // silently returning no findings; Groq's OpenAI-compatible
                // endpoint enforces tool_choice and hard-fails the call:
                //
                //   400: Tool choice is none, but model called a tool
                //
                // Same defect, invisible on one provider and fatal on the other.
                // See docs/engineering-log.md, "the prompt that asked for a tool
                // that was not there".
                reviewer(ReviewArea.LOGISTICS, "logistics-v3"),
                reviewer(ReviewArea.FINANCE, "finance-v3"));
    }

    private ReviewerAgent reviewer(ReviewArea area, String promptVersion) {
        if (!prefetchRules) {
            return new ReviewerAgent(area, promptVersion, prompts, reviewModel, cache);
        }
        List<EvidenceNeed> needs = rulebookFor(area, promptVersion);
        return new ReviewerAgent(area, promptVersion, prompts, reviewModel, cache,
                context -> gatherer.gather(needs, List.of(), context));
    }

    /**
     * Which slice of the rulebook a reviewer would have fetched for itself.
     *
     * <p>Mirrors the tool each prompt is told to call, so pre-resolving gives
     * the reviewer exactly what tool calling would have. QUALITY gets nothing
     * because it has no reference tool - it reads the audit report.
     *
     * <p>The single-agent baseline gets everything, because one prompt covering
     * all five areas would have called all four tools - which is what it did
     * when this was measured with tools enabled.
     */
    private static List<EvidenceNeed> rulebookFor(ReviewArea area, String promptVersion) {
        if (promptVersion.startsWith("single")) {
            return List.of(EvidenceNeed.values());
        }
        return switch (area) {
            case COMPLETENESS -> List.of(EvidenceNeed.REQUIRED_DOCUMENTS);
            case COMPLIANCE -> List.of(EvidenceNeed.COMPLIANCE_RULES);
            // Two, not one. The logistics prompt judges case weight against the
            // manual handling limit, which lives in FINANCE_THRESHOLDS - so
            // supplying only LOGISTICS_REQUIREMENTS asked a reviewer for a
            // comparison against a number it was never given. With tools enabled
            // it fetched both; pre-resolving silently dropped one, which is
            // exactly the difference an optimisation justified as "same SQL, same
            // rows, same prompts" is not allowed to make.
            //
            // Fixed together with logistics-v3 because they are two halves of one
            // defect: a prompt asking for things this configuration does not
            // provide. Splitting them would mean two measurements to establish
            // one fix.
            case LOGISTICS -> List.of(EvidenceNeed.LOGISTICS_REQUIREMENTS,
                    EvidenceNeed.FINANCE_THRESHOLDS);
            case FINANCE -> List.of(EvidenceNeed.FINANCE_THRESHOLDS);
            case QUALITY -> List.of();
        };
    }

    /**
     * @param verifier null to run without adversarial verification
     */
    private ReviewGraph graph(ReviewerAgent gate, List<ReviewerAgent> reviewers,
                              VerifierAgent verifier) throws Exception {
        // No checkpoint saver: the measurement runs to completion rather than
        // pausing for a human, and a paused fixture would just hang the run.
        return new ReviewGraph(gate, reviewers, 2, conflicts, grounding,
                verifier, gatherer, policy, null);
    }
}
