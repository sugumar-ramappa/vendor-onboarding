package com.learning.onboarding.agents;

import com.learning.onboarding.domain.ReviewArea;
import com.learning.onboarding.graph.ConflictDetector;
import com.learning.onboarding.graph.ReviewGraph;
import org.bsc.langgraph4j.GraphStateException;
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
 * means creating {@code compliance-v2.txt} and editing this line - a diff that
 * shows up in review, rather than an edit to a file nobody is watching.
 */
@Configuration
public class ReviewerAgentsConfig {

    @Bean
    public ReviewerAgent complianceReviewer(PromptLibrary prompts, ReviewModel model) {
        return new ReviewerAgent(ReviewArea.COMPLIANCE, "compliance-v1", prompts, model);
    }

    @Bean
    public ReviewerAgent qualityReviewer(PromptLibrary prompts, ReviewModel model) {
        return new ReviewerAgent(ReviewArea.QUALITY, "quality-v1", prompts, model);
    }

    @Bean
    public ReviewerAgent completenessReviewer(PromptLibrary prompts, ReviewModel model) {
        return new ReviewerAgent(ReviewArea.COMPLETENESS, "completeness-v1", prompts, model);
    }

    @Bean
    public ReviewerAgent logisticsReviewer(PromptLibrary prompts, ReviewModel model) {
        return new ReviewerAgent(ReviewArea.LOGISTICS, "logistics-v1", prompts, model);
    }

    @Bean
    public ReviewerAgent financeReviewer(PromptLibrary prompts, ReviewModel model) {
        return new ReviewerAgent(ReviewArea.FINANCE, "finance-v1", prompts, model);
    }

    /**
     * The pipeline. Spring injects every ReviewerAgent bean above, so adding a
     * sixth review area is one bean method and nothing else.
     */
    @Bean
    public ReviewGraph reviewGraph(List<ReviewerAgent> reviewers,
                                   ConflictDetector conflictDetector)
            throws GraphStateException {
        return new ReviewGraph(reviewers, ReviewGraph.DEFAULT_CONCURRENCY, conflictDetector);
    }
}
