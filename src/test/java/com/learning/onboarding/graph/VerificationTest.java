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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Grounding and adversarial verification.
 *
 * <p>Two guarantees are being defended here, and they pull in opposite
 * directions. A fabricated citation must never reach a human, and a genuine
 * finding must never be dropped because a challenge was uncertain. Getting
 * either wrong is worse than having no verifier.
 */
class VerificationTest {

    private static final String CERTIFICATE = """
            ELECTRICAL SAFETY CERTIFICATE
            Scope of certification: Hand tools and non-powered garden implements
            Tested to: EN 62841
            Valid until: 12 April 2027
            """;

    private final PromptLibrary prompts = new PromptLibrary();
    private final GroundingCheck grounding = new GroundingCheck();

    private static ReviewContext context(ExtractionSource source) {
        var documents = List.of(new SubmittedDocument("cert.pdf",
                DocumentType.ELECTRICAL_SAFETY_CERTIFICATE, CERTIFICATE, 1));

        var application = new VendorApplication(
                "APP-1", "Acme Tools Ltd",
                ProductCategory.POWER_TOOLS, DeliveryModel.DISTRIBUTION_CENTRE,
                Instant.now().plus(60, ChronoUnit.DAYS),
                List.of(new Sku("ACM-DRL-18V", "18V cordless drill", "5012345678900",
                        6, new BigDecimal("12.4"), false)),
                documents);

        return new ReviewContext(application, documents, Map.of(),
                Map.of("cert.pdf", source));
    }

    private static ReviewFinding finding(Severity severity, String documentId, String quote) {
        var details = new AgentFinding(severity, "a problem",
                List.of(new Evidence(documentId, 1, quote)),
                CheckType.SEMANTIC, 0.85, null);

        return ReviewFinding.unverified(UUID.randomUUID().toString(),
                ReviewArea.COMPLIANCE, details,
                new FindingSource(UUID.randomUUID(), "compliance-v2", "stub", Instant.now()));
    }

    // ----------------------------------------------------------- grounding --

    @Test
    @DisplayName("a real quote is grounded")
    void realQuoteIsGrounded() {
        assertEquals(GroundingCheck.Result.GROUNDED,
                grounding.check(
                        finding(Severity.BLOCKING, "cert.pdf",
                                "Hand tools and non-powered garden implements"),
                        context(ExtractionSource.NATIVE_TEXT)));
    }

    @Test
    @DisplayName("whitespace differences do not fail a genuine citation")
    void whitespaceIsNormalised() {
        // PDF extraction inserts line breaks a model will not reproduce.
        assertEquals(GroundingCheck.Result.GROUNDED,
                grounding.check(
                        finding(Severity.BLOCKING, "cert.pdf",
                                "Hand   tools and\nnon-powered garden implements"),
                        context(ExtractionSource.NATIVE_TEXT)));
    }

    @Test
    @DisplayName("an invented quote is ungrounded")
    void inventedQuoteIsCaught() {
        assertEquals(GroundingCheck.Result.UNGROUNDED,
                grounding.check(
                        finding(Severity.BLOCKING, "cert.pdf",
                                "Scope: all power tools and battery products"),
                        context(ExtractionSource.NATIVE_TEXT)),
                "a plausible-sounding clause that is not in the document is the "
                        + "worst thing this system can produce");
    }

    @Test
    @DisplayName("a citation to a document that was never submitted is ungrounded")
    void unknownDocumentIsCaught() {
        assertEquals(GroundingCheck.Result.UNGROUNDED,
                grounding.check(
                        finding(Severity.BLOCKING, "renewal-2027.pdf", "anything"),
                        context(ExtractionSource.NATIVE_TEXT)));
    }

    @Test
    @DisplayName("a scanned document cannot be checked, and says so")
    void scannedDocumentIsUnverifiable() {
        assertEquals(GroundingCheck.Result.UNVERIFIABLE,
                grounding.check(
                        finding(Severity.BLOCKING, "cert.pdf", "anything at all"),
                        context(ExtractionSource.MODEL_VISION)),
                "a scan has no source text to match against - requiring one would "
                        + "fail every finding from every scanned certificate");
    }

    // -------------------------------------------------------- verification --

    private VerifierAgent verifier(VerifierAgent.VerifierModel model) {
        return new VerifierAgent("verifier-v1", prompts, model);
    }

    private static VerifierAgent.VerifierModel verdictOf(Verdict verdict) {
        return new VerifierAgent.VerifierModel() {
            @Override
            public Verdict challenge(String system, String user) {
                return verdict;
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };
    }

    @Test
    @DisplayName("a refuted finding is marked as not surviving")
    void refutedFindingDoesNotSurvive() {
        var verdict = verifier(verdictOf(Verdict.disproved(
                "a renewed certificate appears later in the pack",
                List.of(new Evidence("cert.pdf", 1, "Valid until: 12 April 2027")))))
                .challenge(finding(Severity.BLOCKING, "cert.pdf", "Tested to: EN 62841"),
                        context(ExtractionSource.NATIVE_TEXT));

        assertTrue(verdict.disproved());
        assertFalse(verdict.reason().isBlank(),
                "an unexplained refutation is indistinguishable from the verifier "
                        + "malfunctioning");
    }

    @Test
    @DisplayName("a verifier that cannot run leaves the finding standing")
    void verifierFailureLeavesFindingStanding() {
        VerifierAgent.VerifierModel broken = new VerifierAgent.VerifierModel() {
            @Override
            public Verdict challenge(String system, String user) {
                throw new RuntimeException("rate limited");
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        var verdict = verifier(broken).challenge(
                finding(Severity.BLOCKING, "cert.pdf", "Tested to: EN 62841"),
                context(ExtractionSource.NATIVE_TEXT));

        assertFalse(verdict.disproved(),
                "a dropped finding means a non-compliant vendor is approved; a "
                        + "surviving false positive costs a human five minutes");
        assertTrue(verdict.reason().contains("could not run"),
                "the review must show the challenge did not happen, rather than "
                        + "letting a reader assume it passed");
    }

    @Test
    @DisplayName("only serious findings are challenged")
    void onlySeriousFindingsAreChallenged() {
        assertTrue(VerifierAgent.worthChallenging(finding(Severity.BLOCKING, "cert.pdf", "x")));
        assertTrue(VerifierAgent.worthChallenging(finding(Severity.MAJOR, "cert.pdf", "x")));

        assertFalse(VerifierAgent.worthChallenging(finding(Severity.MINOR, "cert.pdf", "x")),
                "nobody rejects a vendor over a MINOR finding, so the model call "
                        + "spent challenging one buys nothing");
        assertFalse(VerifierAgent.worthChallenging(finding(Severity.INFO, "cert.pdf", "x")));
    }

    @Test
    @DisplayName("the challenge shows the verifier the finding AND the documents")
    void challengeCarriesEverythingNeeded() {
        StringBuilder seen = new StringBuilder();
        VerifierAgent.VerifierModel recording = new VerifierAgent.VerifierModel() {
            @Override
            public Verdict challenge(String system, String user) {
                seen.append(user);
                return Verdict.survives("stands");
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        verifier(recording).challenge(
                finding(Severity.BLOCKING, "cert.pdf", "Tested to: EN 62841"),
                context(ExtractionSource.NATIVE_TEXT));

        assertTrue(seen.toString().contains("THE FINDING YOU ARE CHALLENGING"));
        assertTrue(seen.toString().contains("Tested to: EN 62841"),
                "the verifier needs the quoted evidence to judge whether it "
                        + "supports the claim");
        assertTrue(seen.toString().contains("<untrusted"),
                "and the documents, spotlighted exactly as the reviewer saw them");
    }

    // ------------------------------------------------ the graph, end to end --

    /** A gate that always passes, so every fixture reaches the reviewers. */
    private ReviewerAgent passingGate() {
        return new ReviewerAgent(ReviewArea.COMPLETENESS, "completeness-v2", prompts,
                new ReviewModel() {
                    @Override
                    public ModelReply review(String system, String user) {
                        return ModelReply.of(List.of());
                    }

                    @Override
                    public String modelName() {
                        return "stub";
                    }
                });
    }

    /** Gate that always passes, one reviewer, optional verifier, no checkpointing. */
    private ReviewGraph graph(ReviewModel model, VerifierAgent verifier) throws Exception {
        return new ReviewGraph(passingGate(), reviewers(prompts, model), 2,
                new ConflictDetector(), grounding, verifier, EvidenceGatherer.NONE,
                new PolicyProperties(Severity.BLOCKING, Severity.MAJOR, 30, 5_000_000L), null);
    }

    private static List<ReviewerAgent> reviewers(PromptLibrary prompts, ReviewModel model) {
        return List.of(new ReviewerAgent(ReviewArea.COMPLIANCE, "compliance-v2",
                prompts, model));
    }

    private static ReviewModel modelReturning(AgentFinding... findings) {
        return new ReviewModel() {
            @Override
            public ModelReply review(String system, String user) {
                return ModelReply.of(List.of(findings));
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };
    }

    @Test
    @DisplayName("an ungrounded finding never reaches a human")
    void ungroundedFindingIsDiscarded() throws Exception {
        var fabricated = new AgentFinding(Severity.BLOCKING, "certificate covers everything",
                List.of(new Evidence("cert.pdf", 1, "Scope: all power tools")),
                CheckType.SEMANTIC, 0.95, null);

        var graph = graph(modelReturning(fabricated), verifier(verdictOf(Verdict.survives("stands"))));

        ReviewState result = graph.review(context(ExtractionSource.NATIVE_TEXT));

        assertTrue(result.survivingFindings().isEmpty(),
                "a finding quoting text absent from the document is fabricated, "
                        + "and no challenge makes it usable");
        assertEquals(1, result.discarded().size(),
                "and it is recorded, because a reviewer inventing citations is a "
                        + "prompt problem worth knowing about");
    }

    @Test
    @DisplayName("a grounded finding survives an unsuccessful challenge")
    void groundedFindingSurvives() throws Exception {
        var genuine = new AgentFinding(Severity.BLOCKING, "scope does not cover drills",
                List.of(new Evidence("cert.pdf", 1,
                        "Hand tools and non-powered garden implements")),
                CheckType.SEMANTIC, 0.95, "ACM-DRL-18V");

        var graph = graph(modelReturning(genuine),
                verifier(verdictOf(Verdict.survives("could not refute this"))));

        assertEquals(1, graph.review(context(ExtractionSource.NATIVE_TEXT))
                .survivingFindings().size());
    }

    @Test
    @DisplayName("minor findings skip the challenge, saving the call")
    void minorFindingsAreNotChallenged() throws Exception {
        AtomicInteger challenges = new AtomicInteger();
        VerifierAgent.VerifierModel counting = new VerifierAgent.VerifierModel() {
            @Override
            public Verdict challenge(String system, String user) {
                challenges.incrementAndGet();
                return Verdict.survives("stands");
            }

            @Override
            public String modelName() {
                return "stub";
            }
        };

        var minor = new AgentFinding(Severity.MINOR, "a small gap",
                List.of(new Evidence("cert.pdf", 1, "Tested to: EN 62841")),
                CheckType.SEMANTIC, 0.6, null);

        var graph = graph(modelReturning(minor), verifier(counting));

        ReviewState result = graph.review(context(ExtractionSource.NATIVE_TEXT));

        assertEquals(0, challenges.get());
        assertEquals(1, result.survivingFindings().size(),
                "not challenging it does not mean discarding it");
    }
}
