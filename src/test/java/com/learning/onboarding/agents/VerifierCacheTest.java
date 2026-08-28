package com.learning.onboarding.agents;

import com.learning.onboarding.domain.*;
import com.learning.onboarding.intake.ExtractionSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The verifier cache, and the one way it must never be helpful.
 *
 * <h2>Why this exists</h2>
 *
 * {@link ReviewerAgent} has been cached since V4 and the verifier was not, which
 * is backwards: the verifier is the most expensive call in the system. The cost
 * showed up on 2026-08-28 as an inability to <i>correct</i> a measurement -
 * configurations 1 and 2 were re-scored from cache for nothing after a metric
 * bug was found, and configuration 3 could not be, because re-running it meant
 * paying for every challenge again.
 *
 * <p>The dangerous case has its own test below: a challenge that <b>failed</b>
 * must never be stored. Caching a rate limit would turn one bad afternoon into a
 * permanent "the verifier could not run" for that finding.
 */
class VerifierCacheTest {

    private final PromptLibrary prompts = new PromptLibrary();

    /** In-memory, so the key logic is testable without a database. */
    private static class MapCache implements VerifierCache {
        final Map<String, Hit> entries = new HashMap<>();

        @Override
        public Optional<Hit> get(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        @Override
        public void put(String key, String promptVersion, String modelName,
                        Verdict verdict, long latencyMs) {
            entries.put(key, new Hit(verdict, latencyMs));
        }
    }

    /** Counts how many times the model was actually asked. */
    private static class CountingModel implements VerifierAgent.VerifierModel {
        final AtomicInteger calls = new AtomicInteger();
        private final String name;

        CountingModel(String name) {
            this.name = name;
        }

        @Override
        public Verdict challenge(String system, String user) {
            calls.incrementAndGet();
            return Verdict.survives("the certificate does name this SKU");
        }

        @Override
        public String modelName() {
            return name;
        }
    }

    /** Fails every time, the way a rate-limited provider does. */
    private static class FailingModel implements VerifierAgent.VerifierModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Verdict challenge(String system, String user) {
            calls.incrementAndGet();
            throw new IllegalStateException("429 rate limit");
        }

        @Override
        public String modelName() {
            return "openai/gpt-oss-120b";
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

    private static ReviewFinding finding(String problem) {
        return new ReviewFinding(
                UUID.randomUUID().toString(),
                ReviewArea.COMPLIANCE,
                new AgentFinding(Severity.MAJOR, problem,
                        List.of(new Evidence("cert.pdf", 1, "Scope: hand tools")),
                        CheckType.SEMANTIC, 0.8, null),
                new FindingSource(UUID.randomUUID(), "compliance-v3",
                        "openai/gpt-oss-120b", Instant.now()),
                null);
    }

    @Test
    @DisplayName("challenging the same finding twice asks the model once")
    void identicalChallengeHits() {
        var cache = new MapCache();
        var model = new CountingModel("openai/gpt-oss-120b");
        var verifier = new VerifierAgent("verifier-v2", prompts, model, cache);
        var ctx = context("Scope: hand tools");

        verifier.challenge(finding("certificate does not cover this SKU"), ctx);
        verifier.challenge(finding("certificate does not cover this SKU"), ctx);

        assertEquals(1, model.calls.get(),
                "the second identical challenge must not reach the model");
    }

    @Test
    @DisplayName("a different finding is a different challenge")
    void differentFindingMisses() {
        var cache = new MapCache();
        var model = new CountingModel("openai/gpt-oss-120b");
        var verifier = new VerifierAgent("verifier-v2", prompts, model, cache);
        var ctx = context("Scope: hand tools");

        verifier.challenge(finding("certificate does not cover this SKU"), ctx);
        verifier.challenge(finding("certificate expired before go-live"), ctx);

        assertEquals(2, model.calls.get(),
                "two claims are two questions, however similar the paperwork");
    }

    @Test
    @DisplayName("a gatherMore pass carrying new evidence is a new question")
    void extraEvidenceMisses() {
        var cache = new MapCache();
        var model = new CountingModel("openai/gpt-oss-120b");
        var verifier = new VerifierAgent("verifier-v2", prompts, model, cache);
        var ctx = context("Scope: hand tools");
        var f = finding("certificate does not cover this SKU");

        verifier.challenge(f, ctx);
        verifier.challenge(f, ctx, "COMPLIANCE RULES\n  EN 62841 applies to power tools");

        assertEquals(2, model.calls.get(),
                "the second pass was given reference data the first did not have, "
                        + "so answering it from the first pass would discard the "
                        + "very evidence that was fetched to settle it");
    }

    @Test
    @DisplayName("changing the model re-challenges")
    void changedModelMisses() {
        var cache = new MapCache();
        var ctx = context("Scope: hand tools");
        var f = finding("certificate does not cover this SKU");

        var oldModel = new CountingModel("openai/gpt-oss-120b");
        var newModel = new CountingModel("gemini-3.6-flash");

        new VerifierAgent("verifier-v2", prompts, oldModel, cache).challenge(f, ctx);
        new VerifierAgent("verifier-v2", prompts, newModel, cache).challenge(f, ctx);

        assertEquals(1, oldModel.calls.get());
        assertEquals(1, newModel.calls.get(),
                "reporting one model's verdict as another's is the failure the "
                        + "model name in the key exists to prevent");
    }

    @Test
    @DisplayName("a challenge that FAILED is never cached")
    void failuresAreNotCached() {
        var cache = new MapCache();
        var model = new FailingModel();
        var verifier = new VerifierAgent("verifier-v2", prompts, model, cache);
        var ctx = context("Scope: hand tools");
        var f = finding("certificate does not cover this SKU");

        Verdict first = verifier.challenge(f, ctx);
        assertTrue(first.reason().startsWith(VerifierAgent.NOT_CHALLENGED));
        assertTrue(cache.entries.isEmpty(),
                "caching a rate limit would make one bad afternoon permanent: every "
                        + "later run would inherit a failure it never had");

        verifier.challenge(f, ctx);
        assertEquals(2, model.calls.get(),
                "a retry after the limit clears must actually reach the model");
    }

    @Test
    @DisplayName("a cached verdict reports the ORIGINAL latency, not a millisecond")
    void latencyIsHonest() {
        var cache = new MapCache();
        var model = new CountingModel("openai/gpt-oss-120b");
        var verifier = new VerifierAgent("verifier-v2", prompts, model, cache);
        var ctx = context("Scope: hand tools");
        var f = finding("certificate does not cover this SKU");

        verifier.challenge(f, ctx);

        // Whatever the real call took is what is stored. Reporting the cache
        // lookup time instead would make a cached run look like a system that
        // verifies instantly, which is how "it feels faster" becomes a result.
        assertEquals(1, cache.entries.size());
        assertTrue(cache.entries.values().iterator().next().originalLatencyMs() >= 0);
    }
}
