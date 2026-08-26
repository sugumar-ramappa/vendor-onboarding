package com.learning.onboarding.agents;

/**
 * What one model call consumed, as reported by the provider.
 *
 * <h2>Why this exists</h2>
 *
 * {@code docs/performance-and-cost.md} says it plainly:
 *
 * <blockquote>
 * "Token spend is currently unmeasured. Latency was observed from log
 * timestamps; nothing records prompt tokens, completion tokens, or cost per
 * review. That is the first task, not the last. Optimising an unmeasured cost is
 * guesswork, and 'it feels faster' is not a claim that survives questioning."
 * </blockquote>
 *
 * The {@code audit_entry} table has carried {@code prompt_tokens} and
 * {@code completion_tokens} columns since the first migration. Nothing ever
 * wrote to them, because {@code AuditEntry} had no fields for them and the model
 * call discarded the response metadata by asking only for the parsed entity.
 *
 * <h2>Reported, never estimated</h2>
 *
 * Both counts come from the provider's own usage block. A token count computed
 * client-side is a guess with a decimal point on it: tokenisation is
 * model-specific, the prompt is rewritten by the framework before it is sent,
 * and any local approximation drifts from the number actually billed.
 *
 * When a provider omits usage - some do, and cached responses have none by
 * definition - the fields stay null rather than being filled with a zero. Zero
 * is a measurement; null is an admission, and the difference matters when the
 * question is "what did this run cost".
 */
public record TokenUsage(Integer promptTokens, Integer completionTokens) {

    private static final TokenUsage UNKNOWN = new TokenUsage(null, null);

    /** No usage reported - a cached reply, or a provider that does not say. */
    public static TokenUsage unknown() {
        return UNKNOWN;
    }

    public boolean isKnown() {
        return promptTokens != null || completionTokens != null;
    }

    /**
     * Prompt plus completion, or null when neither is known.
     *
     * <p>Deliberately not "0 when unknown". Summing a run of ten calls where
     * three reported nothing must not look like a run that cost 30% less.
     */
    public Integer totalTokens() {
        if (promptTokens == null && completionTokens == null) {
            return null;
        }
        return (promptTokens == null ? 0 : promptTokens)
                + (completionTokens == null ? 0 : completionTokens);
    }
}
