package com.learning.onboarding.graph;

import com.learning.onboarding.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conflict detection.
 *
 * <p>The negative cases matter as much as the positive ones. A detector that
 * reports every difference as a contradiction is noise, and noise is what gets a
 * review tool switched off - so most of these assert that something is NOT a
 * conflict.
 */
class ConflictDetectorTest {

    private final ConflictDetector detector = new ConflictDetector();

    private static ReviewFinding finding(ReviewArea area, Severity severity,
                                         String sku, String documentId) {
        var agentFinding = new AgentFinding(severity, severity + " in " + area,
                List.of(new Evidence(documentId, 1, "quoted text from " + documentId)),
                CheckType.SEMANTIC, 0.8, sku);

        return ReviewFinding.unverified(
                UUID.randomUUID().toString(), area, agentFinding,
                new FindingSource(UUID.randomUUID(), "v1", "stub", Instant.now()));
    }

    @Test
    @DisplayName("two reviewers far apart on the same SKU is a conflict")
    void skuDisputeIsDetected() {
        var conflicts = detector.detect(List.of(
                finding(ReviewArea.COMPLIANCE, Severity.BLOCKING, "ACM-DRL-18V", "cert.pdf"),
                finding(ReviewArea.LOGISTICS, Severity.INFO, "ACM-DRL-18V", "edi.pdf")));

        assertEquals(1, conflicts.size());
        assertInstanceOf(Conflict.SkuDispute.class, conflicts.getFirst());
        assertEquals("SKU ACM-DRL-18V", conflicts.getFirst().subject());
    }

    @Test
    @DisplayName("adjacent severities are agreement, not conflict")
    void adjacentSeveritiesAreNotAConflict() {
        var conflicts = detector.detect(List.of(
                finding(ReviewArea.COMPLIANCE, Severity.BLOCKING, "ACM-DRL-18V", "cert.pdf"),
                finding(ReviewArea.LOGISTICS, Severity.MAJOR, "ACM-DRL-18V", "edi.pdf")));

        assertTrue(conflicts.isEmpty(),
                "two reviewers weighting the same problem slightly differently is "
                        + "not worth a human's time");
    }

    @Test
    @DisplayName("one reviewer's own findings are never a conflict")
    void sameAreaIsNeverAConflict() {
        var conflicts = detector.detect(List.of(
                finding(ReviewArea.COMPLIANCE, Severity.BLOCKING, "ACM-DRL-18V", "cert.pdf"),
                finding(ReviewArea.COMPLIANCE, Severity.INFO, "ACM-DRL-18V", "cert.pdf")));

        assertTrue(conflicts.isEmpty(),
                "one reviewer raising findings of differing severity is doing its job");
    }

    @Test
    @DisplayName("different SKUs are not a conflict")
    void differentSkusAreNotAConflict() {
        var conflicts = detector.detect(List.of(
                finding(ReviewArea.COMPLIANCE, Severity.BLOCKING, "ACM-DRL-18V", "cert.pdf"),
                finding(ReviewArea.LOGISTICS, Severity.INFO, "ACM-HAM-16", "edi.pdf")));

        assertTrue(conflicts.isEmpty(),
                "two reviewers looking at two different items are not disagreeing");
    }

    @Test
    @DisplayName("the same document with far-apart severities is a weaker conflict")
    void documentDisputeIsDetected() {
        var conflicts = detector.detect(List.of(
                finding(ReviewArea.COMPLIANCE, Severity.BLOCKING, null, "cert.pdf"),
                finding(ReviewArea.FINANCE, Severity.INFO, null, "cert.pdf")));

        assertEquals(1, conflicts.size());
        assertInstanceOf(Conflict.DocumentDispute.class, conflicts.getFirst());
        assertEquals("document cert.pdf", conflicts.getFirst().subject());
    }

    @Test
    @DisplayName("a pair is reported once, as the stronger kind")
    void skuDisputeSupersedesDocumentDispute() {
        // Same SKU AND same document. Reporting both would show a human the same
        // disagreement twice under two names.
        var conflicts = detector.detect(List.of(
                finding(ReviewArea.COMPLIANCE, Severity.BLOCKING, "ACM-DRL-18V", "cert.pdf"),
                finding(ReviewArea.QUALITY, Severity.INFO, "ACM-DRL-18V", "cert.pdf")));

        assertEquals(1, conflicts.size());
        assertInstanceOf(Conflict.SkuDispute.class, conflicts.getFirst(),
                "the SKU dispute is the stronger evidence and should win");
    }

    @Test
    @DisplayName("unrelated findings on different documents are not a conflict")
    void unrelatedFindingsAreNotAConflict() {
        var conflicts = detector.detect(List.of(
                finding(ReviewArea.COMPLIANCE, Severity.BLOCKING, null, "cert.pdf"),
                finding(ReviewArea.FINANCE, Severity.INFO, null, "accounts.pdf")));

        assertTrue(conflicts.isEmpty(),
                "five reviewers reporting different problems is the normal case, "
                        + "not a contradiction");
    }

    @Test
    @DisplayName("the description carries both positions")
    void descriptionShowsBothSides() {
        var conflict = detector.detect(List.of(
                finding(ReviewArea.COMPLIANCE, Severity.BLOCKING, "ACM-DRL-18V", "cert.pdf"),
                finding(ReviewArea.LOGISTICS, Severity.INFO, "ACM-DRL-18V", "edi.pdf")))
                .getFirst();

        String described = conflict.describe();

        // A conflict is never resolved, so the output must show a human both
        // sides rather than a verdict on which is right.
        assertTrue(described.contains("COMPLIANCE"));
        assertTrue(described.contains("LOGISTICS"));
        assertTrue(described.contains("BLOCKING"));
        assertTrue(described.contains("INFO"));
    }

    @Test
    @DisplayName("nothing to compare produces nothing")
    void emptyAndSingleFindingProduceNoConflicts() {
        assertTrue(detector.detect(List.of()).isEmpty());
        assertTrue(detector.detect(List.of(
                finding(ReviewArea.COMPLIANCE, Severity.BLOCKING, "ACM-1", "cert.pdf")))
                .isEmpty());
    }
}
