package com.learning.onboarding.agents;

import com.learning.onboarding.domain.ReviewArea;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
