package com.learning.onboarding.agents;

import com.learning.onboarding.domain.AgentFinding;
import com.learning.onboarding.domain.AuditEntry;
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
    private final ReviewCache cache;

    public ReviewerAgent(ReviewArea area, String promptVersion,
                         PromptLibrary prompts, ReviewModel model) {
        this(area, promptVersion, prompts, model, ReviewCache.NONE);
    }

    public ReviewerAgent(ReviewArea area, String promptVersion, PromptLibrary prompts,
                         ReviewModel model, ReviewCache cache) {
        this.area = area;
        this.promptVersion = promptVersion;
        this.prompts = prompts;
        this.model = model;
        this.cache = cache;
        // Fail at construction if the prompt is missing, not on the first
        // review. A reviewer with no instructions still produces findings.
        prompts.get(promptVersion);
    }

    public ReviewArea area() {
        return area;
    }

    /**
     * Runs the review and records the call.
     *
     * <p>A failed call returns an outcome carrying a failed {@link AuditEntry}
     * rather than throwing. That is deliberate: the failure is a fact worth
     * recording, and the caller needs it in order to distinguish "nobody looked"
     * from "looked and found nothing". Throwing would lose it unless every
     * caller remembered to catch and record, which is exactly the kind of thing
     * that gets forgotten.
     */
    public ReviewOutcome review(ReviewContext context) {
        UUID callId = UUID.randomUUID();
        String systemPrompt = prompts.get(promptVersion);
        String userPrompt = context.render();
        // The whole prompt, so the audit record can reproduce the call. Both
        // halves matter: the instructions AND the spotlighted documents.
        String fullPrompt = systemPrompt + "\n\n---\n\n" + userPrompt;

        Instant startedAt = Instant.now();
        long start = System.currentTimeMillis();

        // Everything that could change the answer is in the key: area, prompt
        // version, model, and the rendered prompt with its documents. A hit
        // means this exact question has already been asked of this exact model.
        String cacheKey = ReviewCache.key(
                area.name(), promptVersion, model.modelName(), userPrompt);

        var hit = cache.get(cacheKey);
        if (hit.isPresent()) {
            log.info("{} served {} from cache (original call took {}ms)",
                    area, context.applicationId(), hit.get().originalLatencyMs());
            return outcome(hit.get().findings(), callId, context, fullPrompt,
                    hit.get().originalLatencyMs(), startedAt);
        }

        List<AgentFinding> proposed;
        try {
            proposed = model.review(systemPrompt, userPrompt);
        } catch (ReviewModel.ReviewModelException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("{} failed for {} after {}ms: {}",
                    area, context.applicationId(), elapsed, e.getMessage());

            return new ReviewOutcome(List.of(), AuditEntry.failed(
                    callId, context.applicationId(), area.name().toLowerCase(),
                    promptVersion, model.modelName(), fullPrompt, elapsed,
                    classify(e), e.getMessage(), startedAt));
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("{} reviewed {} in {}ms, {} finding(s), prompt {}",
                area, context.applicationId(), elapsed, proposed.size(), promptVersion);

        // Only successful calls are cached. Caching a failure would turn a
        // transient rate limit into a permanent one for this application.
        cache.put(cacheKey, area.name(), promptVersion, model.modelName(),
                proposed, elapsed);

        return outcome(proposed, callId, context, fullPrompt, elapsed, startedAt);
    }

    /** Wraps the model's findings with the things the model may not assert. */
    private ReviewOutcome outcome(List<AgentFinding> proposed, UUID callId,
                                  ReviewContext context, String fullPrompt,
                                  long elapsed, Instant startedAt) {
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

        return new ReviewOutcome(findings, AuditEntry.ok(
                callId, context.applicationId(), area.name().toLowerCase(),
                promptVersion, model.modelName(), fullPrompt,
                summarise(findings), elapsed, startedAt));
    }

    /**
     * A rate limit clears on its own; an unreachable model may not. The caller
     * reacts differently to each, so they are not collapsed into "failed".
     */
    private static AuditEntry.Outcome classify(ReviewModel.ReviewModelException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase();
        Throwable cause = e.getCause();
        String causeMessage = cause == null ? "" : String.valueOf(cause.getMessage()).toLowerCase();
        String all = message + " " + causeMessage;

        if (all.contains("rate") || all.contains("quota") || all.contains("429")
                || all.contains("resource_exhausted")) {
            return AuditEntry.Outcome.RATE_LIMITED;
        }
        if (all.contains("parse") || all.contains("bind") || all.contains("json")) {
            return AuditEntry.Outcome.MALFORMED;
        }
        return AuditEntry.Outcome.UNAVAILABLE;
    }

    /**
     * The response as recorded.
     *
     * <p>Deliberately the structured findings rather than raw model text: the
     * findings are what the system acted on, and the raw text before binding is
     * not something anyone would read.
     */
    private static String summarise(List<ReviewFinding> findings) {
        if (findings.isEmpty()) {
            return "no findings";
        }
        StringBuilder sb = new StringBuilder();
        for (ReviewFinding f : findings) {
            sb.append('[').append(f.severity()).append("] ")
              .append(f.problem());
            if (f.details().isSkuSpecific()) {
                sb.append("  (SKU ").append(f.details().skuRef()).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
