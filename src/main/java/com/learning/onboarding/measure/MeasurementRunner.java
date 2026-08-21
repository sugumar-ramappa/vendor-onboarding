package com.learning.onboarding.measure;

import com.learning.onboarding.agents.*;
import com.learning.onboarding.domain.ReviewArea;
import com.learning.onboarding.graph.ConflictDetector;
import com.learning.onboarding.graph.GroundingCheck;
import com.learning.onboarding.graph.ReviewGraph;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

    private final FixtureLoader loader;
    private final PromptLibrary prompts;
    private final ReviewModel reviewModel;
    private final ReviewCache cache;
    private final VerifierAgent verifier;
    private final GroundingCheck grounding;
    private final ConflictDetector conflicts;

    public MeasurementRunner(FixtureLoader loader, PromptLibrary prompts,
                             ReviewModel reviewModel, ReviewCache cache,
                             VerifierAgent verifier, GroundingCheck grounding,
                             ConflictDetector conflicts) {
        this.loader = loader;
        this.prompts = prompts;
        this.reviewModel = reviewModel;
        this.cache = cache;
        this.verifier = verifier;
        this.grounding = grounding;
        this.conflicts = conflicts;
    }

    @Override
    public void run(String... args) throws Exception {
        List<Fixture> fixtures = loader.loadAll();
        var harness = new MeasurementHarness(loader);
        var results = new ArrayList<MeasurementHarness.Result>();

        System.out.printf("%n=== measurement: %d fixtures, %d planted defects, %d clean ===%n%n",
                fixtures.size(),
                fixtures.stream().mapToInt(f -> f.expected().size()).sum(),
                fixtures.stream().filter(Fixture::clean).count());

        // 1. One reviewer covering all five areas. The baseline the whole
        //    project exists to beat - or to fail to beat, which would be a more
        //    interesting result and worth reporting either way.
        results.add(harness.run("single agent, all five areas",
                graph(List.of(reviewer(ReviewArea.COMPLIANCE, "single-v1")), null),
                fixtures));

        // 2. Five isolated reviewers, no verification.
        results.add(harness.run("five agents, no verifier",
                graph(fiveReviewers(), null), fixtures));

        // 3. The same five, now challenged. Their output comes from cache, so
        //    the only new cost is the verifier itself.
        results.add(harness.run("five agents + verifier",
                graph(fiveReviewers(), verifier), fixtures));

        System.out.printf("%n=== results ===%n%n");
        results.forEach(r -> System.out.println("  " + r.summary() + "\n"));

        System.out.println("""
                  Recall and false positives are reported together on purpose.
                  A system that flags everything has perfect recall and is useless;
                  one that flags nothing has a perfect false-positive rate and is
                  equally useless. Either number alone can be gamed.
                """);
    }

    private List<ReviewerAgent> fiveReviewers() {
        return List.of(
                reviewer(ReviewArea.COMPLETENESS, "completeness-v2"),
                reviewer(ReviewArea.COMPLIANCE, "compliance-v2"),
                reviewer(ReviewArea.QUALITY, "quality-v2"),
                reviewer(ReviewArea.LOGISTICS, "logistics-v2"),
                reviewer(ReviewArea.FINANCE, "finance-v2"));
    }

    private ReviewerAgent reviewer(ReviewArea area, String promptVersion) {
        return new ReviewerAgent(area, promptVersion, prompts, reviewModel, cache);
    }

    /**
     * @param verifier null to run without adversarial verification
     */
    private ReviewGraph graph(List<ReviewerAgent> reviewers, VerifierAgent verifier)
            throws Exception {
        return new ReviewGraph(reviewers, 2, conflicts, grounding, verifier);
    }
}
