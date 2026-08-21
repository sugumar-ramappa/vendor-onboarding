package com.learning.onboarding.agents;

import com.learning.onboarding.domain.AgentFinding;
import com.learning.onboarding.domain.FindingSource;
import com.learning.onboarding.domain.ReviewArea;
import com.learning.onboarding.domain.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One reviewer: builds the prompt, calls the model, stamps the result.
 *
 * <p>The same class serves every review area. Compliance and quality differ only
 * in which prompt they load and which {@link ReviewArea} their findings are
 * stamped with - the mechanics of prompting, binding and provenance are
 * identical, and duplicating them per area would mean five places to fix a bug
 * in the grounding rules.
 *
 * <h2>What this class is actually responsible for</h2>
 * <ol>
 *   <li><b>Isolation.</b> It receives a {@link ReviewContext} containing the
 *       application and its documents, and nothing else. No reviewer is given
 *       another reviewer's findings, which is what stops the second one
 *       anchoring on the first.</li>
 *   <li><b>Spotlighting.</b> Vendor text is wrapped as data before it reaches
 *       the model.</li>
 *   <li><b>Stamping.</b> The area and the {@link FindingSource} are applied
 *       here, after the model has answered, so the model cannot assert either.</li>
 *   <li><b>Failing closed.</b> A model that cannot be reached produces an
 *       exception, never an empty result.</li>
 * </ol>
 */
public class ReviewerAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewerAgent.class);

    private final ReviewArea area;
    private final String promptVersion;
    private final PromptLibrary prompts;
    private final ReviewModel model;

    public ReviewerAgent(ReviewArea area, String promptVersion,
                         PromptLibrary prompts, ReviewModel model) {
        this.area = area;
        this.promptVersion = promptVersion;
        this.prompts = prompts;
        this.model = model;
        // Fail at construction if the prompt is missing, not on the first
        // review. A reviewer with no instructions still produces findings.
        prompts.get(promptVersion);
    }

    public ReviewArea area() {
        return area;
    }

    /**
     * @throws ReviewModel.ReviewModelException if the model could not answer.
     *         Deliberately propagated rather than swallowed: the caller must
     *         escalate to a human, because "the reviewer failed" and "the
     *         reviewer found nothing" are different facts that look identical
     *         once one is turned into an empty list.
     */
    public List<ReviewFinding> review(ReviewContext context) {
        UUID callId = UUID.randomUUID();
        String systemPrompt = prompts.get(promptVersion);
        String userPrompt = context.render();

        long start = System.currentTimeMillis();
        List<AgentFinding> proposed = model.review(systemPrompt, userPrompt);
        long elapsed = System.currentTimeMillis() - start;

        log.info("{} reviewed {} in {}ms, {} finding(s), prompt {}",
                area, context.applicationId(), elapsed, proposed.size(), promptVersion);

        FindingSource source = new FindingSource(
                callId, promptVersion, model.modelName(), Instant.now());

        List<ReviewFinding> findings = new ArrayList<>(proposed.size());
        for (int i = 0; i < proposed.size(); i++) {
            findings.add(ReviewFinding.unverified(
                    // Deterministic within a call, so a finding id is stable if
                    // the same call is replayed from the audit log.
                    "%s-%s-%d".formatted(area.name().toLowerCase(), callId, i),
                    area,          // set HERE, not by the model
                    proposed.get(i),
                    source));      // set HERE, not by the model
        }
        return findings;
    }
}
