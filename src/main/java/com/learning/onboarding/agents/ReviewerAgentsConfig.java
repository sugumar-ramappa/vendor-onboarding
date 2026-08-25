package com.learning.onboarding.agents;

import com.learning.onboarding.domain.ReviewArea;
import com.learning.onboarding.config.PolicyProperties;
import com.learning.onboarding.graph.ConflictDetector;
import com.learning.onboarding.graph.EvidenceGatherer;
import com.learning.onboarding.graph.GroundingCheck;
import com.learning.onboarding.graph.ReviewGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires one reviewer per area.
 *
 * <p>Each is the same {@link ReviewerAgent} class with a different prompt, which
 * is what makes adding the remaining three a configuration change rather than
 * three new classes. It also means the grounding, spotlighting and provenance
 * rules exist in exactly one place.
 *
 * <p>Prompt versions are named here on purpose. Changing a reviewer's behaviour
 * means creating a new prompt file and editing this line - a diff that shows up
 * in review, rather than an edit to a file nobody is watching.
 *
 * <p><b>v2 exists because v1 did not say what to do with skuRef for a
 * vendor-level finding.</b> The model invented "ALL", which made two unrelated
 * findings look like two opinions about the same SKU and produced a false
 * conflict. v1 is kept unchanged so the findings already stored against it can
 * still be explained - which is the entire reason prompts are versioned rather
 * than edited.
 */
@Configuration
public class ReviewerAgentsConfig {

    @Bean
    public ReviewerAgent complianceReviewer(PromptLibrary prompts, ReviewModel model,
                                          ReviewCache cache) {
        return new ReviewerAgent(ReviewArea.COMPLIANCE, "compliance-v2", prompts, model, cache);
    }

    @Bean
    public ReviewerAgent qualityReviewer(PromptLibrary prompts, ReviewModel model,
                                          ReviewCache cache) {
        return new ReviewerAgent(ReviewArea.QUALITY, "quality-v2", prompts, model, cache);
    }

    @Bean
    public ReviewerAgent completenessReviewer(PromptLibrary prompts, ReviewModel model,
                                          ReviewCache cache) {
        return new ReviewerAgent(ReviewArea.COMPLETENESS, "completeness-v2", prompts, model, cache);
    }

    @Bean
    public ReviewerAgent logisticsReviewer(PromptLibrary prompts, ReviewModel model,
                                          ReviewCache cache) {
        return new ReviewerAgent(ReviewArea.LOGISTICS, "logistics-v2", prompts, model, cache);
    }

    @Bean
    public ReviewerAgent financeReviewer(PromptLibrary prompts, ReviewModel model,
                                          ReviewCache cache) {
        return new ReviewerAgent(ReviewArea.FINANCE, "finance-v2", prompts, model, cache);
    }

    /**
     * The challenger.
     *
     * <p>Its own prompt and its own context. A model asked to check work it just
     * produced agrees with itself; one that did not write the finding has no
     * such attachment, and that separation is the entire mechanism.
     */
    @Bean
    public VerifierAgent verifierAgent(PromptLibrary prompts,
                                       VerifierAgent.VerifierModel model) {
        return new VerifierAgent("verifier-v2", prompts, model);
    }

    /**
     * The pipeline. Spring injects every ReviewerAgent bean above, so adding a
     * sixth review area is one bean method and nothing else.
     */
    @Bean
    public ReviewGraph reviewGraph(
            List<ReviewerAgent> reviewers,
            ConflictDetector conflictDetector,
            GroundingCheck groundingCheck,
            VerifierAgent verifier,
            EvidenceGatherer gatherer,
            PolicyProperties policy,
            BaseCheckpointSaver checkpointSaver,
            @Value("${onboarding.model.concurrency:2}") int concurrency)
            throws GraphStateException {

        // Completeness is a GATE, not a peer. It runs first and alone, and the
        // other four only run if it passes - which is what makes an incomplete
        // pack cost one model call instead of five.
        ReviewerAgent completeness = reviewers.stream()
                .filter(r -> r.area() == ReviewArea.COMPLETENESS)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no completeness reviewer"));

        List<ReviewerAgent> substantive = reviewers.stream()
                .filter(r -> r.area() != ReviewArea.COMPLETENESS)
                .toList();

        return new ReviewGraph(completeness, substantive, concurrency, conflictDetector,
                groundingCheck, verifier, gatherer, policy, checkpointSaver);
    }

    /**
     * Where a paused review is stored while it waits for a person.
     *
     * <p>In memory for now. Swapping in {@code langgraph4j-postgres-saver} makes
     * a pause survive a restart, which is what a decision taking days actually
     * requires - the interface is the same either way.
     */
    @Bean
    public BaseCheckpointSaver checkpointSaver() {
        return new MemorySaver();
    }
}
