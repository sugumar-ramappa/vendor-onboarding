package com.learning.onboarding.graph;

import com.learning.onboarding.agents.*;
import com.learning.onboarding.domain.*;
import com.learning.onboarding.config.PolicyProperties;
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
            public ModelReply review(String system, String user) {
                return ModelReply.of(List.of(finding("finding from " + label)));
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

    private ReviewerAgent gate(ReviewModel model) {
        return agent(ReviewArea.COMPLETENESS, "completeness-v2", model);
    }

    private List<ReviewerAgent> fourReviewers(ReviewModel model) {
        return List.of(
                agent(ReviewArea.COMPLIANCE, "compliance-v2", model),
                agent(ReviewArea.QUALITY, "quality-v2", model),
                agent(ReviewArea.LOGISTICS, "logistics-v2", model),
                agent(ReviewArea.FINANCE, "finance-v2", model));
    }

    /** Gate plus four reviewers. No verification, no checkpointing. */
    private ReviewGraph graph(ReviewModel model) throws Exception {
        return new ReviewGraph(gate(model), fourReviewers(model), 4,
                new ConflictDetector(), new GroundingCheck(), null,
                EvidenceGatherer.NONE,
                new PolicyProperties(Severity.BLOCKING, Severity.MAJOR, 30, 5_000_000L), null);
    }

    @Test
    @DisplayName("all five reviewers run and their findings accumulate")
    void allReviewersContribute() throws Exception {
        var graph = graph(modelReturning("x"));

        ReviewState result = graph.review(context());

        assertEquals(5, result.findings().size(),
                "gate plus four reviewers - fewer means the appender channel is "
                        + "overwriting instead of accumulating");

        Set<ReviewArea> areas = result.findings().stream()
                .map(ReviewFinding::area).collect(java.util.stream.Collectors.toSet());
        assertEquals(5, areas.size(), "every review area should be represented");
    }

    @Test
    @DisplayName("a surviving MAJOR finding goes to a human, even though it does not block")
    void survivingMajorEscalates() throws Exception {
        // The gap this closes: with one threshold at BLOCKING, a MAJOR finding
        // that survived an adversarial challenge was AUTO_CLEARED and shown to
        // nobody - while Severity.MAJOR documents itself as "a human may waive
        // it". Waiving requires being shown.
        ReviewState result = graph(modelReturning("x")).review(context());

        assertTrue(result.findings().stream().allMatch(f -> f.severity() == Severity.MAJOR),
                "precondition: nothing here should be BLOCKING");
        assertTrue(result.needsHuman(), "a surviving MAJOR must reach a person");
        assertEquals("ESCALATED", result.verdict());
    }

    @Test
    @DisplayName("a MINOR finding still clears automatically")
    void survivingMinorDoesNotEscalate() throws Exception {
        // The other half. Without this the change reads as "escalate
        // everything", which is the same as having no gate at all.
        ReviewModel minorOnly = new ReviewModel() {
            @Override
            public ModelReply review(String system, String user) {
                return ModelReply.of(List.of(new AgentFinding(Severity.MINOR, "a documentation nit",
                        List.of(new Evidence("cert.pdf", 1, "Scope: hand tools")),
                        CheckType.SEMANTIC, 0.8, null)));
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        ReviewState result = graph(minorOnly).review(context());

        assertEquals(5, result.findings().size());
        assertFalse(result.needsHuman(), "MINOR findings are a report, not an interruption");
        assertEquals("AUTO_CLEARED", result.verdict());
    }

    @Test
    @DisplayName("the graph reaches every node")
    void everyNodeRuns() throws Exception {
        var graph = graph(modelReturning("x"));

        List<String> trace = graph.review(context()).trace();

        for (String node : List.of("COMPLETENESS", "fanOut", "gather", "decide")) {
            assertTrue(trace.contains(node), "never reached " + node);
        }
        for (ReviewArea area : ReviewArea.values()) {
            assertTrue(trace.contains(area.name()), "never reached " + area);
        }

        // The gate writes to a replace channel and the reviewers to an appender;
        // trace() stitches them together. Any node appearing twice means a
        // pre-fork write leaked back onto an appender - see ReviewState.
        assertEquals(trace.size(), Set.copyOf(trace).size(),
                "no node should appear twice in a run without a verify cycle: " + trace);
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
            public ModelReply review(String system, String user) {
                peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    concurrent.decrementAndGet();
                }
                return ModelReply.of(List.of(finding("slow")));
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        var graph = graph(slow);

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
            public ModelReply review(String system, String user) {
                if (system.contains("compliance reviewer")) {
                    throw new ReviewModel.ReviewModelException("rate limited", null);
                }
                return ModelReply.of(List.of(finding("ok")));
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        ReviewState result = graph(failsForCompliance).review(context());

        assertEquals(4, result.findings().size(), "the gate plus three working reviewers must survive");
        assertEquals(1, result.failures().size(), "the failure must be recorded");
        assertTrue(result.failures().getFirst().startsWith("COMPLIANCE"));
    }

    @Test
    @DisplayName("a failed reviewer is not the same as a clean one")
    void failureIsDistinguishableFromEmpty() throws Exception {
        ReviewModel alwaysFails = new ReviewModel() {
            @Override
            public ModelReply review(String system, String user) {
                throw new ReviewModel.ReviewModelException("model unavailable", null);
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        ReviewState failed = graph(alwaysFails).review(context());

        ReviewModel findsNothing = new ReviewModel() {
            @Override
            public ModelReply review(String system, String user) {
                return ModelReply.of(List.of());
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        ReviewState clean = graph(findsNothing).review(context());

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
            public ModelReply review(String system, String user) {
                systemPrompts.add(system);
                return ModelReply.of(List.of());
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        graph(recording).review(context());

        assertEquals(5, systemPrompts.size(),
                "five distinct prompts expected - a shared one would mean five "
                        + "reviewers doing the same review");
    }

    // ------------------------------------------------------------------------
    // The three behaviours that need a graph rather than CompletableFuture.
    // A plain fan-out is ten lines of Java; these are not.
    // ------------------------------------------------------------------------

    /** A gate that always blocks, so the conditional edge takes the other branch. */
    private static ReviewModel gateBlocks() {
        return new ReviewModel() {
            @Override
            public ModelReply review(String system, String user) {
                if (system.contains("completeness")) {
                    return ModelReply.of(List.of(new AgentFinding(Severity.BLOCKING,
                            "no public liability certificate in the pack",
                            List.of(new Evidence("cert.pdf", 1, "Scope: hand tools")),
                            CheckType.DETERMINISTIC, 1.0, null)));
                }
                return ModelReply.of(List.of(finding("should never run")));
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };
    }

    @Test
    @DisplayName("an incomplete pack skips the four reviewers entirely")
    void incompletePackShortCircuits() throws Exception {
        ReviewState result = graph(gateBlocks()).review(context());

        // The point of the gate is cost, not tidiness. Four reviewers on a pack
        // that is missing a document produce four confident findings about
        // absent evidence, and each one is a model call spent to learn
        // something the gate already knew.
        for (ReviewArea area : List.of(ReviewArea.COMPLIANCE, ReviewArea.QUALITY,
                ReviewArea.LOGISTICS, ReviewArea.FINANCE)) {
            assertFalse(result.trace().contains(area.name()),
                    area + " ran on a pack the gate had already rejected");
        }

        assertTrue(result.trace().contains("requestDocuments"));
        assertEquals("DOCUMENTS_REQUESTED", result.verdict());
        assertEquals(1, result.findings().size(), "only the gate's finding");
    }

    /** A verifier that says "cannot tell" the first n times, then concedes. */
    private VerifierAgent verifierUnresolvedFor(int passes, AtomicInteger calls) {
        return new VerifierAgent("verifier-v1", prompts, new VerifierAgent.VerifierModel() {
            @Override
            public Verdict challenge(String systemPrompt, String userPrompt) {
                return calls.incrementAndGet() <= passes
                        ? Verdict.unresolved("cannot tell from the pack alone",
                        "whether policy PL-4471029 has a territorial endorsement",
                        List.of(EvidenceNeed.COMPLIANCE_RULES))
                        : Verdict.survives("checked, the finding stands");
            }

            @Override
            public String modelName() {
                return "stub";
            }
        });
    }

    /** Returns the rulebook slice that was actually asked for, and nothing else. */
    private static EvidenceGatherer fixedEvidence() {
        return (needs, questions, context) -> needs.isEmpty() ? "" :
                "REFERENCE DATA\n  - EN 62841 is accepted for POWER_TOOLS\n"
                        + "  - fetched: " + needs + "\n";
    }

    private ReviewGraph graph(ReviewModel model, VerifierAgent verifier,
                              EvidenceGatherer gatherer) throws Exception {
        return new ReviewGraph(gate(model), fourReviewers(model), 4,
                new ConflictDetector(), new GroundingCheck(), verifier, gatherer,
                new PolicyProperties(Severity.BLOCKING, Severity.MAJOR, 30, 5_000_000L), null);
    }

    @Test
    @DisplayName("an unresolved verdict sends the finding round again")
    void unresolvedFindingsGoRoundTheCycle() throws Exception {
        var calls = new AtomicInteger();
        ReviewState result = graph(modelReturning("x"), verifierUnresolvedFor(5, calls),
                fixedEvidence()).review(context());

        long verifyNodes = result.trace().stream().filter(n -> n.startsWith("verify")).count();
        assertTrue(verifyNodes > 1,
                "verify ran once, so the cycle never fired: " + result.trace());
        assertTrue(result.trace().contains("gatherMore"));

        // One extra pass, not the maximum. The bound is a backstop; the loop is
        // driven by whether anything is still unresolved, so a verifier that
        // settles on the second look costs one extra pass and no more.
        assertEquals(1, result.verifyPasses(),
                "the cycle should stop as soon as it converges, not run to the bound");
        assertTrue(result.unresolvedQuestions().isEmpty(),
                "nothing should still be open once the cycle has exited");
    }

    @Test
    @DisplayName("the cycle is bounded, and giving up keeps the finding")
    void theCycleTerminatesFailingClosed() throws Exception {
        var calls = new AtomicInteger();
        // Never resolves. Without a bound this runs until the recursion limit
        // throws; with one it stops and escalates.
        ReviewState result = graph(modelReturning("x"),
                verifierUnresolvedFor(Integer.MAX_VALUE, calls), fixedEvidence())
                .review(context());

        assertEquals(ReviewGraph.MAX_VERIFY_PASSES, result.verifyPasses());
        assertTrue(result.trace().contains("decide"), "the graph must still reach a decision");

        // The finding is unresolved, not disproved. Dropping it would mean a
        // vendor clears onboarding because the verifier could not make up its
        // mind - the fail-open failure this project is built to avoid.
        assertFalse(result.survivingFindings().isEmpty(),
                "an undecided challenge must leave the finding standing");
        assertTrue(result.needsHuman(),
                "unresolved after the bound is exactly what a person is for");
    }

    @Test
    @DisplayName("gatherMore's evidence reaches the verifier on the next pass")
    void gatheredEvidenceReachesTheVerifier() throws Exception {
        List<String> promptsSeen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        var calls = new AtomicInteger();

        var verifier = new VerifierAgent("verifier-v1", prompts, new VerifierAgent.VerifierModel() {
            @Override
            public Verdict challenge(String systemPrompt, String userPrompt) {
                promptsSeen.add(userPrompt);
                return calls.incrementAndGet() <= 5
                        ? Verdict.unresolved("cannot tell", "which standards are accepted",
                        List.of(EvidenceNeed.COMPLIANCE_RULES))
                        : Verdict.survives("the reference data settles it");
            }

            @Override
            public String modelName() {
                return "stub";
            }
        });

        graph(modelReturning("x"), verifier, fixedEvidence()).review(context());

        assertFalse(promptsSeen.getFirst().contains("EN 62841"),
                "the first challenge cannot already carry the evidence - nothing had "
                        + "asked for it yet, and fetching it unconditionally is the cost "
                        + "the cycle exists to avoid");
        assertTrue(promptsSeen.stream().anyMatch(p -> p.contains("EN 62841")),
                "gatherMore fetched reference data and the verifier never saw it - "
                        + "the cycle would be asking the same question twice");

        // Ours, and above the vendor's documents. Untrusted material stays last
        // so it cannot sit above the instructions it might try to override.
        String withEvidence = promptsSeen.stream()
                .filter(p -> p.contains("EN 62841")).findFirst().orElseThrow();
        assertTrue(withEvidence.indexOf("EN 62841")
                        < withEvidence.indexOf("THE APPLICATION AND ITS DOCUMENTS"),
                "fetched evidence must sit above the vendor's documents");
    }

    @Test
    @DisplayName("a later pass re-challenges only what is still open")
    void laterPassesAreScopedToUnresolvedFindings() throws Exception {
        var firstPass = new AtomicInteger();
        var laterPasses = new AtomicInteger();

        // Only COMPLIANCE comes back unresolved. The other four are settled on
        // pass 1 and must not be paid for again.
        var verifier = new VerifierAgent("verifier-v1", prompts, new VerifierAgent.VerifierModel() {
            @Override
            public Verdict challenge(String systemPrompt, String userPrompt) {
                boolean isRetry = userPrompt.contains("EN 62841");
                (isRetry ? laterPasses : firstPass).incrementAndGet();

                if (userPrompt.contains("raised by: COMPLIANCE") && !isRetry) {
                    return Verdict.unresolved("cannot tell", "which standards are accepted",
                            List.of(EvidenceNeed.COMPLIANCE_RULES));
                }
                return Verdict.survives("checked");
            }

            @Override
            public String modelName() {
                return "stub";
            }
        });

        ReviewState result =
                graph(modelReturning("x"), verifier, fixedEvidence()).review(context());

        assertEquals(5, firstPass.get(), "every finding should be challenged once");
        assertEquals(1, laterPasses.get(),
                "the second pass must re-challenge only the unresolved finding - "
                        + "re-running all five is 2N model calls to learn nothing new");
        assertTrue(result.survivingFindings().size() >= 5,
                "verdicts settled on pass 1 must be carried through, not lost");
    }

    @Test
    @DisplayName("with nothing to fetch, the cycle stops instead of spinning to the bound")
    void nothingToFetchEndsTheCycleEarly() throws Exception {
        var calls = new AtomicInteger();

        // EvidenceGatherer.NONE: no database, nothing to look up. Another pass
        // would ask the same question with the same information.
        ReviewState result = graph(modelReturning("x"),
                verifierUnresolvedFor(Integer.MAX_VALUE, calls), EvidenceGatherer.NONE)
                .review(context());

        assertEquals(1, result.verifyPasses(),
                "the loop should exit on the first empty gather, not run to the bound");
        assertTrue(result.trace().contains("gatherMore (nothing to fetch)"));

        // Exiting early must not change the outcome - unresolved is not disproved.
        assertFalse(result.survivingFindings().isEmpty());
        assertTrue(result.needsHuman());
    }

    @Test
    @DisplayName("only the reference data the verifier named is fetched")
    void theFetchIsNarrowedToWhatWasAskedFor() throws Exception {
        List<List<EvidenceNeed>> asked = java.util.Collections.synchronizedList(
                new java.util.ArrayList<>());
        var calls = new AtomicInteger();

        var verifier = new VerifierAgent("verifier-v2", prompts, new VerifierAgent.VerifierModel() {
            @Override
            public Verdict challenge(String systemPrompt, String userPrompt) {
                return calls.incrementAndGet() <= 5
                        ? Verdict.unresolved("cannot tell", "which standards are accepted",
                        List.of(EvidenceNeed.COMPLIANCE_RULES))
                        : Verdict.survives("settled");
            }

            @Override
            public String modelName() {
                return "stub";
            }
        });

        EvidenceGatherer recording = (needs, questions, context) -> {
            asked.add(needs);
            return "REFERENCE DATA\n  - EN 62841 accepted\n";
        };

        graph(modelReturning("x"), verifier, recording).review(context());

        assertEquals(1, asked.size(), "gatherMore should have run once");
        assertEquals(List.of(EvidenceNeed.COMPLIANCE_RULES), asked.getFirst(),
                "the fetch must be exactly what the verifier named - fetching all four "
                        + "tables regardless is the version this enum replaced");
    }

    @Test
    @DisplayName("the menu the model chooses from is generated from the enum")
    void thePromptMenuComesFromTheEnum() {
        String menu = EvidenceNeed.menu();

        for (EvidenceNeed need : EvidenceNeed.values()) {
            assertTrue(menu.contains(need.name()),
                    need + " is missing from the menu, so the model would never pick it "
                            + "and the gatherer branch for it would be dead code");
        }
    }

    @Test
    @DisplayName("undecided without naming a need does not spend a pass")
    void unresolvedWithoutANeedDoesNotLoop() throws Exception {
        var calls = new AtomicInteger();

        // Honest uncertainty with no lookup attached. There is nothing to fetch,
        // so going round again would cost a model call to reach the same place.
        var verifier = new VerifierAgent("verifier-v2", prompts, new VerifierAgent.VerifierModel() {
            @Override
            public Verdict challenge(String systemPrompt, String userPrompt) {
                calls.incrementAndGet();
                return Verdict.unresolved("the pack is ambiguous",
                        "whether the signatory was authorised");   // no EvidenceNeed
            }

            @Override
            public String modelName() {
                return "stub";
            }
        });

        ReviewState result = graph(modelReturning("x"), verifier, fixedEvidence()).review(context());

        assertEquals(0, result.verifyPasses(), "no pass should have been spent");
        assertFalse(result.trace().contains("gatherMore"));
        assertEquals(5, calls.get(), "each finding challenged exactly once");

        // Still fails closed: undecided is not disproved.
        assertFalse(result.survivingFindings().isEmpty());
        assertTrue(result.needsHuman());
    }

    @Test
    @DisplayName("a review needing a human pauses and resumes from the checkpoint")
    void pausesBeforeHumanReviewAndResumes() throws Exception {
        var saver = new org.bsc.langgraph4j.checkpoint.MemorySaver();
        var calls = new AtomicInteger();

        var graph = new ReviewGraph(gate(modelReturning("x")),
                fourReviewers(modelReturning("x")), 4, new ConflictDetector(),
                new GroundingCheck(), verifierUnresolvedFor(Integer.MAX_VALUE, calls),
                fixedEvidence(), new PolicyProperties(Severity.BLOCKING, Severity.MAJOR, 30, 5_000_000L), saver);

        var config = org.bsc.langgraph4j.RunnableConfig.builder()
                .threadId("APP-2026-0113").build();

        ReviewState paused = graph.review(context(), config);

        assertTrue(paused.needsHuman());
        assertFalse(paused.trace().contains(ReviewGraph.HUMAN_REVIEW_NODE),
                "the run must stop BEFORE the human node, not run it and pretend "
                        + "a decision was made: " + paused.trace());

        int modelCallsBeforePause = calls.get();

        ReviewState resumed = graph.resume(config).orElseThrow();

        assertTrue(resumed.trace().contains(ReviewGraph.HUMAN_REVIEW_NODE),
                "resuming should continue into the human node");
        assertEquals(modelCallsBeforePause, calls.get(),
                "resuming re-ran model calls - the checkpoint is not being used, "
                        + "which is the entire reason for pausing rather than blocking");
        assertEquals(paused.findings().size(), resumed.findings().size(),
                "the findings a human was shown must be the findings that survive "
                        + "the resume");
    }
}
