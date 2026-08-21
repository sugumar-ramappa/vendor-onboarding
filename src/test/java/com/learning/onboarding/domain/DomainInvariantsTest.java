package com.learning.onboarding.domain;

import com.learning.onboarding.config.PolicyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The domain rules that the rest of the system relies on.
 *
 * <p>No Spring, no model, no database - these run in milliseconds and would fail
 * loudly if someone weakened an invariant. That is the point of putting the
 * security properties in the type system: they are testable without any
 * infrastructure at all.
 */
class DomainInvariantsTest {

    private static final FindingSource SOURCE = new FindingSource(
            UUID.randomUUID(), "compliance-v1", "gemini-3.6-flash", Instant.now());

    private static AgentFinding agentFinding(Severity severity) {
        return new AgentFinding(severity, "ISO 9001 certificate expired",
                List.of(Evidence.of("iso-cert.pdf", "Valid until: 12 April 2026")),
                CheckType.DETERMINISTIC, 0.95);
    }

    @Nested
    @DisplayName("grounding")
    class Grounding {

        @Test
        @DisplayName("a finding cannot exist without evidence")
        void findingRequiresEvidence() {
            var e = assertThrows(IllegalArgumentException.class, () ->
                    new AgentFinding(Severity.BLOCKING, "certificate expired",
                            List.of(), CheckType.DETERMINISTIC, 0.9));
            assertTrue(e.getMessage().contains("evidence"));
        }

        @Test
        @DisplayName("evidence cannot exist without a quote")
        void evidenceRequiresQuote() {
            assertThrows(IllegalArgumentException.class, () ->
                    new Evidence("iso-cert.pdf", 1, "  "));
        }

        @Test
        @DisplayName("evidence is defensively copied")
        void evidenceIsImmutable() {
            var refs = new java.util.ArrayList<>(List.of(
                    Evidence.of("a.pdf", "quoted text")));
            var d = new AgentFinding(Severity.MINOR, "claim", refs, CheckType.SEMANTIC, 0.5);
            refs.clear();
            assertEquals(1, d.evidence().size(), "caller mutated the finding after construction");
        }
    }

    @Nested
    @DisplayName("finding source")
    class FindingSourceRules {

        @Test
        @DisplayName("a finding cannot exist without a prompt version")
        void promptVersionRequired() {
            var e = assertThrows(IllegalArgumentException.class, () ->
                    new FindingSource(UUID.randomUUID(), "", "gemini-3.6-flash", Instant.now()));
            assertTrue(e.getMessage().contains("promptVersion"));
        }
    }

    @Nested
    @DisplayName("the model has no authority")
    class NoAuthority {

        /**
         * Reflection rather than a comment, so the guarantee cannot rot. If
         * someone adds an `approved` field to the model-facing record, this
         * fails - which is exactly when you want to be interrupted.
         */
        @Test
        @DisplayName("AgentFinding exposes no decision or area field")
        void agentFindingCannotDecideOrSelfLabel() {
            var forbidden = List.of("approved", "approve", "decision", "verdict",
                    "blocked", "area", "correlationid", "promptversion");

            for (var component : AgentFinding.class.getRecordComponents()) {
                assertFalse(forbidden.contains(component.getName().toLowerCase()),
                        "AgentFinding.%s lets the model assert something it must not"
                                .formatted(component.getName()));
            }
        }
    }

    @Nested
    @DisplayName("fail closed")
    class FailClosed {

        @Test
        @DisplayName("an unverified finding still reaches the reviewer")
        void unverifiedSurvives() {
            var f = ReviewFinding.unverified("f1", ReviewArea.COMPLIANCE,
                    agentFinding(Severity.BLOCKING), SOURCE);
            assertTrue(f.survives(),
                    "a finding the verifier never reached must not be silently dropped");
        }

        @Test
        @DisplayName("a refuted finding does not")
        void refutedDoesNotSurvive() {
            var f = ReviewFinding.unverified("f1", ReviewArea.COMPLIANCE,
                            agentFinding(Severity.BLOCKING), SOURCE)
                    .withVerdict(Verdict.refuted(
                            "a renewed certificate is present later in the pack",
                            List.of(Evidence.of("iso-cert-2027.pdf", "Valid until: 2027"))));
            assertFalse(f.survives());
        }

        @Test
        @DisplayName("a verdict must explain itself")
        void verdictNeedsReasoning() {
            assertThrows(IllegalArgumentException.class, () ->
                    new Verdict(true, "", List.of()));
        }
    }

    @Nested
    @DisplayName("the decision is made in Java")
    class DecisionGate {

        private final PolicyProperties policy =
                new PolicyProperties(Severity.BLOCKING, 30, 5_000_000L);

        @Test
        @DisplayName("only BLOCKING blocks, under the configured threshold")
        void blocksAtThreshold() {
            assertTrue(policy.blocks(Severity.BLOCKING));
            assertFalse(policy.blocks(Severity.MAJOR));
            assertFalse(policy.blocks(Severity.MINOR));
            assertFalse(policy.blocks(null));
        }

        @Test
        @DisplayName("lowering the threshold in config changes what blocks")
        void thresholdIsConfiguration() {
            var strict = new PolicyProperties(Severity.MAJOR, 30, 5_000_000L);
            assertTrue(strict.blocks(Severity.MAJOR),
                    "policy comes from configuration, never from a prompt");
        }

        @Test
        @DisplayName("severity order is load-bearing")
        void severityOrdering() {
            assertTrue(Severity.BLOCKING.atLeast(Severity.MAJOR));
            assertTrue(Severity.MAJOR.atLeast(Severity.MAJOR));
            assertFalse(Severity.MINOR.atLeast(Severity.MAJOR));
        }
    }
}
