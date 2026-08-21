package com.learning.onboarding.graph;

import com.learning.onboarding.agents.*;
import com.learning.onboarding.domain.*;
import com.learning.onboarding.intake.ExtractionSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pipeline as a graph, with the model stubbed.
 *
 * <p>What matters here is not what the reviewers conclude - that is measured in
 * step 8 against fixtures. It is that all five actually run, that their findings
 * accumulate rather than overwrite, that one failing does not lose the other
 * four, and that a failure is distinguishable from an empty result.
 */
class ReviewGraphTest {

    private final PromptLibrary prompts = new PromptLibrary();

    private static ReviewContext context() {
        var documents = List.of(new SubmittedDocument("cert.pdf",
                DocumentType.ELECTRICAL_SAFETY_CERTIFICATE,
                "Scope: hand tools\nValid until: 12 April 2027", 1));

        var application = new VendorApplication(
                "APP-2026-0113", "Acme Tools Ltd",
                ProductCategory.POWER_TOOLS, DeliveryModel.DISTRIBUTION_CENTRE,
                Instant.now().plus(60, ChronoUnit.DAYS),
                List.of(new Sku("ACM-DRL-18V", "18V cordless drill", "5012345678900",
                        6, new BigDecimal("12.4"), false)),
                documents);

        return new ReviewContext(application, documents, Map.of(),
                Map.of("cert.pdf", ExtractionSource.NATIVE_TEXT));
    }

    private static AgentFinding finding(String problem) {
        return new AgentFinding(Severity.MAJOR, problem,
                List.of(new Evidence("cert.pdf", 1, "Scope: hand tools")),
                CheckType.SEMANTIC, 0.8, null);
    }

    /** Returns one finding naming itself, so findings can be traced to a reviewer. */
    private static ReviewModel modelReturning(String label) {
        return new ReviewModel() {
            @Override
            public List<AgentFinding> review(String system, String user) {
                return List.of(finding("finding from " + label));
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };
    }

    private ReviewerAgent agent(ReviewArea area, String prompt, ReviewModel model) {
        return new ReviewerAgent(area, prompt, prompts, model);
    }

    private List<ReviewerAgent> allFive(ReviewModel model) {
        return List.of(
                agent(ReviewArea.COMPLETENESS, "completeness-v1", model),
                agent(ReviewArea.COMPLIANCE, "compliance-v1", model),
                agent(ReviewArea.QUALITY, "quality-v1", model),
                agent(ReviewArea.LOGISTICS, "logistics-v1", model),
                agent(ReviewArea.FINANCE, "finance-v1", model));
    }

    @Test
    @DisplayName("all five reviewers run and their findings accumulate")
    void allReviewersContribute() throws Exception {
        var graph = new ReviewGraph(allFive(modelReturning("x")));

        ReviewState result = graph.review(context());

        assertEquals(5, result.findings().size(),
                "one finding per reviewer - fewer means the appender channel is "
                        + "overwriting instead of accumulating");

        Set<ReviewArea> areas = result.findings().stream()
                .map(ReviewFinding::area).collect(java.util.stream.Collectors.toSet());
        assertEquals(5, areas.size(), "every review area should be represented");
    }

    @Test
    @DisplayName("the graph reaches every node")
    void everyNodeRuns() throws Exception {
        var graph = new ReviewGraph(allFive(modelReturning("x")));

        List<String> trace = graph.review(context()).trace();

        assertTrue(trace.contains("intake"));
        assertTrue(trace.contains("gather"));
        for (ReviewArea area : ReviewArea.values()) {
            assertTrue(trace.contains(area.name()), "never reached " + area);
        }
    }

    @Test
    @DisplayName("reviewers run concurrently, not one after another")
    void reviewersRunInParallel() throws Exception {
        // Each reviewer sleeps; if they were sequential the total would be the
        // sum. This is the fan-out that turns nine minutes into two.
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        ReviewModel slow = new ReviewModel() {
            @Override
            public List<AgentFinding> review(String system, String user) {
                peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    concurrent.decrementAndGet();
                }
                return List.of(finding("slow"));
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        var graph = new ReviewGraph(allFive(slow));

        long start = System.currentTimeMillis();
        graph.review(context());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(peak.get() > 1,
                "reviewers ran one at a time - the fan-out is not parallel");
        assertTrue(elapsed < 5 * 120,
                "elapsed " + elapsed + "ms is close to the sequential total, so the "
                        + "reviewers are queuing rather than overlapping");
    }

    @Test
    @DisplayName("one reviewer failing does not lose the other four")
    void oneFailureDoesNotAbortTheRun() throws Exception {
        ReviewModel failsForCompliance = new ReviewModel() {
            @Override
            public List<AgentFinding> review(String system, String user) {
                if (system.contains("compliance reviewer")) {
                    throw new ReviewModel.ReviewModelException("rate limited", null);
                }
                return List.of(finding("ok"));
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        ReviewState result = new ReviewGraph(allFive(failsForCompliance)).review(context());

        assertEquals(4, result.findings().size(), "the four working reviewers must survive");
        assertEquals(1, result.failures().size(), "the failure must be recorded");
        assertTrue(result.failures().getFirst().startsWith("COMPLIANCE"));
    }

    @Test
    @DisplayName("a failed reviewer is not the same as a clean one")
    void failureIsDistinguishableFromEmpty() throws Exception {
        ReviewModel alwaysFails = new ReviewModel() {
            @Override
            public List<AgentFinding> review(String system, String user) {
                throw new ReviewModel.ReviewModelException("model unavailable", null);
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        ReviewState failed = new ReviewGraph(allFive(alwaysFails)).review(context());

        ReviewModel findsNothing = new ReviewModel() {
            @Override
            public List<AgentFinding> review(String system, String user) {
                return List.of();
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        ReviewState clean = new ReviewGraph(allFive(findsNothing)).review(context());

        // Both have zero findings. Only one of them was actually reviewed.
        assertTrue(failed.findings().isEmpty());
        assertTrue(clean.findings().isEmpty());

        assertFalse(failed.allReviewersRan(),
                "nobody looked at this application - it must not be auto-decided");
        assertTrue(clean.allReviewersRan(),
                "five reviewers looked and found nothing - that is a real result");
    }

    @Test
    @DisplayName("each reviewer gets its own prompt")
    void reviewersUseTheirOwnPrompts() throws Exception {
        Set<String> systemPrompts = ConcurrentHashMap.newKeySet();

        ReviewModel recording = new ReviewModel() {
            @Override
            public List<AgentFinding> review(String system, String user) {
                systemPrompts.add(system);
                return List.of();
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        new ReviewGraph(allFive(recording)).review(context());

        assertEquals(5, systemPrompts.size(),
                "five distinct prompts expected - a shared one would mean five "
                        + "reviewers doing the same review");
    }
}
