package com.learning.onboarding.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a call cost is recorded, and "not reported" is not the same as zero.
 *
 * <p>docs/performance-and-cost.md said token spend was unmeasured and called it
 * "the first task, not the last". The columns had existed since the first
 * migration; nothing filled them, because the model call asked only for the
 * parsed entity and threw the response metadata away.
 */
@DisplayName("token accounting")
class TokenUsageTest {

    @Test
    @DisplayName("unknown usage stays null rather than becoming zero")
    void unknownIsNotZero() {
        // Zero is a measurement. Null is an admission. Summing ten calls where
        // three reported nothing must not look like a run that cost 30% less.
        var unknown = TokenUsage.unknown();
        assertThat(unknown.promptTokens()).isNull();
        assertThat(unknown.completionTokens()).isNull();
        assertThat(unknown.totalTokens()).isNull();
        assertThat(unknown.isKnown()).isFalse();
    }

    @Test
    @DisplayName("reported usage totals correctly")
    void knownUsageTotals() {
        var usage = new TokenUsage(1500, 220);
        assertThat(usage.isKnown()).isTrue();
        assertThat(usage.totalTokens()).isEqualTo(1720);
    }

    @Test
    @DisplayName("a partially reported call still totals what it knows")
    void partialUsage() {
        // Some providers report only one side. Treating that as unknown would
        // discard a real number; treating the missing half as zero is the only
        // sane arithmetic, and isKnown() stays true so it is not filtered out.
        var promptOnly = new TokenUsage(900, null);
        assertThat(promptOnly.isKnown()).isTrue();
        assertThat(promptOnly.totalTokens()).isEqualTo(900);
    }

    @Test
    @DisplayName("a reply carries the cost of the call that produced it")
    void replyCarriesUsage() {
        // Not a lastUsage() accessor on the model: reviewers run concurrently
        // and share one model instance, so a mutable "last call" field would be
        // read by whichever reviewer asked next rather than the one that called.
        var reply = new ModelReply(List.of(), new TokenUsage(1200, 180));
        assertThat(reply.usage().totalTokens()).isEqualTo(1380);
    }

    @Test
    @DisplayName("a reply built without usage reports unknown, not zero")
    void replyWithoutUsage() {
        assertThat(ModelReply.of(List.of()).usage().isKnown()).isFalse();
    }

    @Test
    @DisplayName("a cache hit records no usage, because no call was made")
    void cacheHitHasNoUsage() {
        // The distinction the nullable columns exist for: null means "no call",
        // which is different from "a call that reported zero".
        var cached = ModelReply.of(List.of());
        assertThat(cached.usage().isKnown()).isFalse();
    }

    @Test
    @DisplayName("a successful audit entry carries the tokens through to the row")
    void auditEntryCarriesTokens() {
        var entry = com.learning.onboarding.domain.AuditEntry.ok(
                java.util.UUID.randomUUID(), "APP-1", "compliance", "compliance-v2",
                "gemini-3.6-flash", "prompt", "response", 1500, 220, 900L,
                java.time.Instant.now());
        assertThat(entry.promptTokens()).isEqualTo(1500);
        assertThat(entry.completionTokens()).isEqualTo(220);
    }

    @Test
    @DisplayName("a failed call records no tokens - the provider sends no usage with an error")
    void failedCallHasNoTokens() {
        var entry = com.learning.onboarding.domain.AuditEntry.failed(
                java.util.UUID.randomUUID(), "APP-1", "compliance", "compliance-v2",
                "gemini-3.6-flash", "prompt", 400L,
                com.learning.onboarding.domain.AuditEntry.Outcome.RATE_LIMITED,
                "429", java.time.Instant.now());
        assertThat(entry.promptTokens()).isNull();
        assertThat(entry.completionTokens()).isNull();
    }
}
