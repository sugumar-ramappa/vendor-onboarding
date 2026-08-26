package com.learning.onboarding.agents;

import com.learning.onboarding.domain.*;
import com.learning.onboarding.intake.ExtractionSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Caching, and the four ways it could quietly be wrong.
 *
 * <p>A cache that never hits is a slow system. A cache that hits when it should
 * not is a system reporting one model's findings while claiming another's - and
 * nothing about the output looks wrong. The second is what these tests are for.
 */
class ReviewCacheTest {

    private final PromptLibrary prompts = new PromptLibrary();

    /** In-memory cache, so the key logic can be tested without a database. */
    private static class MapCache implements ReviewCache {
        final Map<String, Hit> entries = new HashMap<>();
        final AtomicInteger writes = new AtomicInteger();

        @Override
        public Optional<Hit> get(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        @Override
        public void put(String key, String area, String promptVersion, String modelName,
                        List<AgentFinding> findings, long latencyMs) {
            entries.put(key, new Hit(findings, latencyMs));
            writes.incrementAndGet();
        }
    }

    /** Counts how many times the model was actually asked. */
    private static class CountingModel implements ReviewModel {
        final AtomicInteger calls = new AtomicInteger();
        private final String name;

        CountingModel(String name) {
            this.name = name;
        }

        @Override
        public ModelReply review(String system, String user) {
            calls.incrementAndGet();
            return ModelReply.of(List.of(new AgentFinding(Severity.MAJOR, "a problem",
                    List.of(new Evidence("cert.pdf", 1, "Scope: hand tools")),
                    CheckType.SEMANTIC, 0.8, null)));
        }

        @Override
        public String modelName() {
            return name;
        }
    }

    private static ReviewContext context(String documentText) {
        var documents = List.of(new SubmittedDocument("cert.pdf",
                DocumentType.ELECTRICAL_SAFETY_CERTIFICATE, documentText, 1));

        var application = new VendorApplication(
                "APP-1", "Acme Tools Ltd",
                ProductCategory.POWER_TOOLS, DeliveryModel.DISTRIBUTION_CENTRE,
                Instant.parse("2026-10-20T00:00:00Z"),
                List.of(new Sku("ACM-DRL-18V", "18V cordless drill", "5012345678900",
                        6, new BigDecimal("12.4"), false)),
                documents);

        return new ReviewContext(application, documents, Map.of(),
                Map.of("cert.pdf", ExtractionSource.NATIVE_TEXT));
    }

    @Test
    @DisplayName("an identical review is served from cache")
    void identicalReviewHits() {
        var cache = new MapCache();
        var model = new CountingModel("gemini-3.6-flash");
        var agent = new ReviewerAgent(ReviewArea.COMPLIANCE, "compliance-v2",
                prompts, model, cache);

        agent.review(context("Scope: hand tools"));
        agent.review(context("Scope: hand tools"));

        assertEquals(1, model.calls.get(),
                "the second identical review must not reach the model");
    }

    @Test
    @DisplayName("changing a document re-runs the reviewer")
    void changedDocumentMisses() {
        var cache = new MapCache();
        var model = new CountingModel("gemini-3.6-flash");
        var agent = new ReviewerAgent(ReviewArea.COMPLIANCE, "compliance-v2",
                prompts, model, cache);

        agent.review(context("Scope: hand tools"));
        agent.review(context("Scope: hand tools and power tools"));

        assertEquals(2, model.calls.get(),
                "a corrected certificate is a different question and must be re-asked");
    }

    @Test
    @DisplayName("changing the prompt version re-runs only that reviewer")
    void changedPromptMisses() {
        var cache = new MapCache();
        var model = new CountingModel("gemini-3.6-flash");
        var ctx = context("Scope: hand tools");

        new ReviewerAgent(ReviewArea.COMPLIANCE, "compliance-v1", prompts, model, cache)
                .review(ctx);
        new ReviewerAgent(ReviewArea.COMPLIANCE, "compliance-v2", prompts, model, cache)
                .review(ctx);

        assertEquals(2, model.calls.get(),
                "this is the whole point: editing one prompt re-runs one reviewer");
    }

    @Test
    @DisplayName("changing the model re-runs the reviewer")
    void changedModelMisses() {
        var cache = new MapCache();
        var ctx = context("Scope: hand tools");

        var oldModel = new CountingModel("gemini-3.6-flash");
        var newModel = new CountingModel("gemini-3.7-flash");

        new ReviewerAgent(ReviewArea.COMPLIANCE, "compliance-v2", prompts, oldModel, cache)
                .review(ctx);
        new ReviewerAgent(ReviewArea.COMPLIANCE, "compliance-v2", prompts, newModel, cache)
                .review(ctx);

        assertEquals(1, newModel.calls.get(),
                "serving the previous model's findings would make a measurement "
                        + "compare two things while reporting one");
    }

    @Test
    @DisplayName("two reviewers on the same application do not share an entry")
    void differentAreasDoNotCollide() {
        var cache = new MapCache();
        var model = new CountingModel("gemini-3.6-flash");
        var ctx = context("Scope: hand tools");

        new ReviewerAgent(ReviewArea.COMPLIANCE, "compliance-v2", prompts, model, cache)
                .review(ctx);
        new ReviewerAgent(ReviewArea.QUALITY, "quality-v2", prompts, model, cache)
                .review(ctx);

        assertEquals(2, model.calls.get(),
                "compliance and quality ask different questions about the same pack");
    }

    @Test
    @DisplayName("a failed call is never cached")
    void failuresAreNotCached() {
        var cache = new MapCache();
        ReviewModel failing = new ReviewModel() {
            @Override
            public ModelReply review(String system, String user) {
                throw new ReviewModelException("rate limited", null);
            }

            @Override
            public String modelName() {
                return "gemini-3.6-flash";
            }
        };

        new ReviewerAgent(ReviewArea.COMPLIANCE, "compliance-v2", prompts, failing, cache)
                .review(context("Scope: hand tools"));

        assertEquals(0, cache.writes.get(),
                "caching a rate limit would turn a transient failure into a "
                        + "permanent one for this application");
    }

    @Test
    @DisplayName("a cached review still reports the original latency")
    void cachedReviewKeepsHonestTiming() {
        var cache = new MapCache();
        var model = new CountingModel("gemini-3.6-flash");
        var agent = new ReviewerAgent(ReviewArea.COMPLIANCE, "compliance-v2",
                prompts, model, cache);
        var ctx = context("Scope: hand tools");

        long realLatency = agent.review(ctx).audit().latencyMs();
        long cachedLatency = agent.review(ctx).audit().latencyMs();

        assertEquals(realLatency, cachedLatency,
                "a measurement that reported cache-hit timings would claim the "
                        + "system is faster than it is");
    }

    @Test
    @DisplayName("the key derivation is identical for reader and writer")
    void keyIsStable() {
        String a = ReviewCache.key("COMPLIANCE", "compliance-v2", "gemini-3.6-flash", "prompt");
        String b = ReviewCache.key("COMPLIANCE", "compliance-v2", "gemini-3.6-flash", "prompt");

        assertEquals(a, b);
        assertNotEquals(a,
                ReviewCache.key("QUALITY", "compliance-v2", "gemini-3.6-flash", "prompt"));
    }
}
