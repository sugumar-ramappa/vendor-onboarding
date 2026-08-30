package com.learning.onboarding.measure;

import com.learning.onboarding.agents.ReviewCache;
import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.agents.ReviewModel;
import com.learning.onboarding.agents.ReviewOutcome;
import com.learning.onboarding.agents.ReviewerAgent;
import com.learning.onboarding.agents.PromptLibrary;
import com.learning.onboarding.agents.VerifierAgent;
import com.learning.onboarding.domain.EvidenceNeed;
import com.learning.onboarding.domain.ReviewArea;
import com.learning.onboarding.domain.ReviewFinding;
import com.learning.onboarding.domain.Verdict;
import com.learning.onboarding.graph.EvidenceGatherer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures whether the verifier can tell a false finding from a true one.
 *
 * <pre>
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=calibrate,groq
 * </pre>
 *
 * <h2>Why this exists</h2>
 *
 * The three-configuration measurement reported that the verifier refuted
 * <b>zero</b> findings out of 48, twice. That looks like a result and is not
 * one. Every finding it saw was essentially correct - configuration 2 caught 14
 * of 14 planted defects and its clean-pack findings were all {@code INFO}
 * confirmations. A well-calibrated verifier shown only correct findings should
 * refute nothing, so zero is what a working verifier and a broken one both
 * produce. The measurement could not distinguish them.
 *
 * <p>{@code prompt-eval}, elsewhere in this workspace, had already diagnosed
 * exactly this in its own LLM judge: <i>with only correct answers to grade,
 * false accepts are undetectable - you cannot observe a judge waving through a
 * wrong answer you never showed it.</i> Its fix was a balanced set of 30 correct
 * and 30 deliberately wrong answers, which found that the higher-agreement
 * prompt was the worse judge. This is the same method applied to the verifier.
 *
 * <h2>Where the labels come from, without anyone labelling</h2>
 *
 * Both halves are true by construction, which matters: a calibration set graded
 * by the person who wants a particular answer is not evidence.
 *
 * <ul>
 *   <li><b>False</b> - findings raised on a <i>clean</i> fixture. F16 is a brush
 *       vendor with no hazardous SKU and nothing wrong with its pack, so every
 *       finding on it is false by definition of the fixture, not by judgement.</li>
 *   <li><b>True</b> - findings on a defective fixture that match a planted
 *       defect. The fixture author put the defect there.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 *
 * The reviewer calls are served from the measurement cache, so producing the
 * findings is free. Only the verifier challenges are new.
 */
@Component
@Profile("calibrate")
public class VerifierCalibrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(VerifierCalibrationRunner.class);

    private final FixtureLoader loader;
    private final PromptLibrary prompts;
    private final ReviewModel reviewModel;
    private final ReviewCache cache;
    private final VerifierAgent verifier;
    private final EvidenceGatherer gatherer;

    public VerifierCalibrationRunner(FixtureLoader loader, PromptLibrary prompts,
                                     ReviewModel reviewModel, ReviewCache cache,
                                     VerifierAgent verifier, EvidenceGatherer gatherer) {
        this.loader = loader;
        this.prompts = prompts;
        this.reviewModel = reviewModel;
        this.cache = cache;
        this.verifier = verifier;
        this.gatherer = gatherer;
    }

    /** Clean fixtures. Every finding raised on these is false. */
    @Value("${calibrate.false-fixtures:F16,F15}")
    private String falseFixtures;

    /** Defective fixtures. Findings matching a planted defect are true. */
    @Value("${calibrate.true-fixtures:F17,F20}")
    private String trueFixtures;

    @Override
    public void run(String... args) {
        List<Labelled> set = new ArrayList<>();
        set.addAll(collect(falseFixtures, false));
        set.addAll(collect(trueFixtures, true));

        if (set.isEmpty()) {
            log.error("calibration set is empty - nothing to measure");
            return;
        }

        int falseRefuted = 0;
        int falseTotal = 0;
        int trueRefuted = 0;
        int trueTotal = 0;

        for (Labelled item : set) {
            if (!VerifierAgent.worthChallenging(item.finding())) {
                // The verifier only sees MAJOR and above in production, so
                // scoring it on findings it would never be handed would measure
                // a system that does not exist.
                continue;
            }

            Verdict verdict = verifier.challenge(item.finding(), item.context(), "");
            boolean refuted = verdict.disproved();

            if (item.actuallyTrue()) {
                trueTotal++;
                if (refuted) {
                    trueRefuted++;
                }
            } else {
                falseTotal++;
                if (refuted) {
                    falseRefuted++;
                }
            }

            log.info("{} [{}] {} -> {}", item.fixtureId(),
                    item.actuallyTrue() ? "TRUE " : "FALSE",
                    item.finding().problem(),
                    refuted ? "REFUTED" : verdict.outcome());

            // The verdict without its argument is an assertion, not a result.
            // "It refuted four of six" is a much weaker claim than "it refuted
            // four, and here is what it said" - and the reason is the only part
            // that shows whether it reasoned from the pack or guessed correctly.
            log.info("        because: {}", verdict.reason());
            for (var cited : verdict.evidence()) {
                log.info("        cites  : {} \"{}\"", cited.documentId(), cited.quote());
            }
            if (verdict.needsEvidenceFor() != null) {
                log.info("        blocked on: {}", verdict.needsEvidenceFor());
            }
        }

        report(falseRefuted, falseTotal, trueRefuted, trueTotal);
    }

    /**
     * Reviews each fixture and labels what comes back.
     *
     * <p>The single agent is used throughout rather than the four specialists,
     * because the six fabricated findings this experiment exists to test came
     * from the single agent - four of them {@code BLOCKING}, on a vendor whose
     * SKUs are marked {@code hazardous = false}.
     */
    private List<Labelled> collect(String fixtureIds, boolean expectTrue) {
        List<Labelled> out = new ArrayList<>();

        for (String id : fixtureIds.split(",")) {
            String fixtureId = id.trim();
            if (fixtureId.isEmpty()) {
                continue;
            }
            Fixture fixture = loader.loadAll().stream()
                    .filter(f -> f.id().equals(fixtureId))
                    .findFirst()
                    .orElse(null);
            if (fixture == null) {
                log.warn("no such fixture: {}", fixtureId);
                continue;
            }
            if (fixture.clean() == expectTrue) {
                log.warn("{} is {} - cannot supply {} findings, skipping", fixtureId,
                        fixture.clean() ? "clean" : "defective", expectTrue ? "true" : "false");
                continue;
            }

            ReviewContext context = loader.toContext(fixture);

            // The rulebook is pre-resolved into the prompt exactly as the
            // measurement does it, and for a reason beyond cost: the review
            // cache is keyed on the RENDERED prompt. Building the agent any
            // other way produces a different prompt, misses the cache, and pays
            // Groq to regenerate findings that are already stored - while
            // quietly measuring a different single agent from the one that
            // produced the six fabricated findings.
            //
            // "single" gets every rulebook slice because one prompt covering all
            // five areas would have called all four tools.
            ReviewOutcome outcome = new ReviewerAgent(
                    ReviewArea.COMPLIANCE, "single-v1", prompts, reviewModel, cache,
                    ctx -> gatherer.gather(List.of(EvidenceNeed.values()), List.of(), ctx))
                    .review(context);

            if (!outcome.succeeded()) {
                log.warn("{} review failed - excluded rather than counted as empty", fixtureId);
                continue;
            }

            for (ReviewFinding finding : outcome.findings()) {
                boolean matchesPlanted = fixture.expected().stream()
                        .anyMatch(d -> d.matchedBy(finding));

                // On a defective pack, a finding matching nothing planted might
                // be a real problem the fixture author missed. Scoring it either
                // way would be a guess, so it is left out of the set entirely.
                if (expectTrue && !matchesPlanted) {
                    continue;
                }
                out.add(new Labelled(fixtureId, finding, context, expectTrue));
            }
        }
        return out;
    }

    private void report(int falseRefuted, int falseTotal, int trueRefuted, int trueTotal) {
        log.info("");
        log.info("VERIFIER CALIBRATION - model {}", reviewModel.modelName());
        log.info("  known-FALSE findings refuted:  {} of {}   <- should be high",
                falseRefuted, falseTotal);
        log.info("  known-TRUE  findings refuted:  {} of {}   <- should be zero",
                trueRefuted, trueTotal);
        log.info("");
        log.info("  Neither number means anything alone. A verifier that refutes");
        log.info("  everything scores perfectly on the first line and destroys the");
        log.info("  system; one that refutes nothing scores perfectly on the second.");
        log.info("  The result is the GAP between them.");
    }

    /** One finding with a label that came from the fixture, not from judgement. */
    private record Labelled(String fixtureId, ReviewFinding finding,
                            ReviewContext context, boolean actuallyTrue) {}
}
