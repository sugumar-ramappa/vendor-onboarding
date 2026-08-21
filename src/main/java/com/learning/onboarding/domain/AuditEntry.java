package com.learning.onboarding.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * One model call, recorded.
 *
 * <p>For a system a human has to act on, <b>the audit trail is the product</b>.
 * A finding a reviewer cannot trace back to a prompt and a document is a finding
 * they will not act on, and the second time one turns out to be wrong they stop
 * trusting all of them.
 *
 * <p>Given a finding, this is what lets you retrieve the exact prompt that
 * produced it, the exact response, which model answered, and how long it took.
 *
 * <h2>outcome, and why it is not a boolean</h2>
 * A call can fail in ways that need different responses: a per-minute rate limit
 * clears in a minute, a per-day limit clears at midnight, and a guardrail block
 * is not a failure at all. Collapsing those into success/failure loses the
 * distinction that decides what to do next.
 *
 * @param callId          ties this to the findings it produced
 * @param applicationId   which application, or null for a call outside a review
 * @param nodeName        which graph node made the call
 * @param promptVersion   which prompt - the field that lets you explain a
 *                        result six weeks later
 * @param modelName       which model answered
 * @param promptText      what was sent, including the spotlighted documents
 * @param responseText    what came back, or null on failure
 * @param latencyMs       wall clock
 * @param outcome         how it ended
 * @param failureDetail   why, when it did not end well
 */
public record AuditEntry(
        UUID callId,
        String applicationId,
        String nodeName,
        String promptVersion,
        String modelName,
        String promptText,
        String responseText,
        long latencyMs,
        Outcome outcome,
        String failureDetail,
        Instant startedAt
) implements Serializable {

    public enum Outcome {
        /** The call completed and returned usable findings. */
        OK,
        /** Per-minute rate limit. Clears when the window rolls; worth retrying. */
        RATE_LIMITED,
        /**
         * The daily quota is spent.
         *
         * <p>Distinct from RATE_LIMITED because the operational response is
         * completely different: a per-minute limit clears in a minute, this
         * clears at midnight. Retrying it burns wall clock to fail again, and
         * an operator reading "rate limited" would reasonably assume waiting
         * briefly is enough.
         */
        QUOTA_EXHAUSTED,
        /** Timed out or the model was unreachable. */
        UNAVAILABLE,
        /** Returned something that would not bind to the expected type. */
        MALFORMED,
        /** A guardrail stopped the call. Not a failure - the system working. */
        GUARDRAIL_BLOCKED
    }

    public static AuditEntry ok(UUID callId, String applicationId, String nodeName,
                                String promptVersion, String modelName,
                                String promptText, String responseText,
                                long latencyMs, Instant startedAt) {
        return new AuditEntry(callId, applicationId, nodeName, promptVersion, modelName,
                promptText, responseText, latencyMs, Outcome.OK, null, startedAt);
    }

    public static AuditEntry failed(UUID callId, String applicationId, String nodeName,
                                    String promptVersion, String modelName,
                                    String promptText, long latencyMs,
                                    Outcome outcome, String detail, Instant startedAt) {
        return new AuditEntry(callId, applicationId, nodeName, promptVersion, modelName,
                promptText, null, latencyMs, outcome, detail, startedAt);
    }
}
