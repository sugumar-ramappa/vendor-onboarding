package com.learning.onboarding.agents;

import com.learning.onboarding.domain.AgentFinding;

import java.util.List;

/**
 * The one call a reviewer makes to a model.
 *
 * <p>An interface so {@link ReviewerAgent} can be tested without an API key, a
 * network, or a bill. That matters more than it sounds: the logic worth testing
 * in a reviewer is what it puts in the prompt, what it does with malformed
 * output, and how it behaves when the model is unavailable - none of which needs
 * a real model, and all of which would be slow and non-deterministic with one.
 *
 * <p>The measurement in step 8 uses the real implementation. Everything else
 * uses a stub, which is why the suite runs in seconds and needs no credentials.
 */
public interface ReviewModel {

    /**
     * @param systemPrompt  the reviewer's instructions
     * @param userPrompt    the application and its documents, already spotlighted
     * @return the findings the model reported; empty is a valid answer
     * @throws ReviewModelException if the model could not be reached, refused,
     *         or returned something that would not bind
     */
    ModelReply review(String systemPrompt, String userPrompt);

    /** Which model answered. Recorded against every finding. */
    String modelName();

    /**
     * A model call that could not produce findings.
     *
     * <p>Deliberately not "returns an empty list on failure". An empty list means
     * the reviewer looked and found nothing wrong; a failure means nobody looked.
     * Collapsing the two is the fail-open bug this project exists to avoid, and
     * it is invisible in the output.
     */
    class ReviewModelException extends RuntimeException {
        public ReviewModelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
