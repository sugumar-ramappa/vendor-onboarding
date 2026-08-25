package com.learning.onboarding.measure;

import com.learning.onboarding.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a finding is scored against a planted defect.
 *
 * <p>These exist because of a bug that made the whole experiment meaningless
 * while every test passed: the matcher required an exact {@link ReviewArea},
 * and the single-agent baseline stamps every finding with whichever area slot
 * it was constructed with. Six of eleven planted defects could never match, so
 * the baseline was capped at 0.45 recall by construction and multi-agent won
 * automatically.
 */
class ScoringTest {

    private static final Fixture.ExpectedDefect LOGISTICS_DEFECT =
            new Fixture.ExpectedDefect(ReviewArea.LOGISTICS, Severity.MAJOR, null,
                    List.of("asn"), "no advance ship notice capability");

    private static ReviewFinding finding(ReviewArea area, Severity severity, String problem) {
        return finding(area, severity, problem, null);
    }

    private static ReviewFinding finding(ReviewArea area, Severity severity,
                                         String problem, String skuRef) {
        return ReviewFinding.unverified("F-1", area,
                new AgentFinding(severity, problem,
                        List.of(new Evidence("edi.pdf", 1, "no DESADV")),
                        CheckType.SEMANTIC, 0.8, skuRef),
                new FindingSource(UUID.randomUUID(), "v1", "stub", Instant.now()));
    }

    @Test
    @DisplayName("a defect found by the wrong area still counts as detected")
    void detectionIgnoresArea() {
        // The single agent labels everything COMPLIANCE. It still found it.
        ReviewFinding fromWrongArea =
                finding(ReviewArea.COMPLIANCE, Severity.MAJOR, "vendor cannot send an ASN");

        assertTrue(LOGISTICS_DEFECT.matchedBy(fromWrongArea),
                "scoring detection by area caps the baseline at 0.45 by construction, "
                        + "which makes the comparison an artefact of the harness");
    }

    @Test
    @DisplayName("but routing is only correct when the right area found it")
    void routingRequiresTheRightArea() {
        assertFalse(LOGISTICS_DEFECT.routedCorrectly(
                finding(ReviewArea.COMPLIANCE, Severity.MAJOR, "vendor cannot send an ASN")));

        assertTrue(LOGISTICS_DEFECT.routedCorrectly(
                finding(ReviewArea.LOGISTICS, Severity.MAJOR, "vendor cannot send an ASN")));
    }

    @Test
    @DisplayName("severity and wording still gate a match")
    void theOtherConditionsStillApply() {
        assertFalse(LOGISTICS_DEFECT.matchedBy(
                        finding(ReviewArea.LOGISTICS, Severity.MINOR, "vendor cannot send an ASN")),
                "below the minimum severity");

        assertFalse(LOGISTICS_DEFECT.matchedBy(
                        finding(ReviewArea.LOGISTICS, Severity.MAJOR, "insurance is too low")),
                "does not mention what the defect is about");
    }

    @Test
    @DisplayName("a SKU-specific defect must name the SKU")
    void skuMustMatch() {
        var skuDefect = new Fixture.ExpectedDefect(ReviewArea.COMPLIANCE, Severity.MAJOR,
                "ACM-DRL-18V", List.of("scope"), "scope excludes the drill");

        ReviewFinding vendorLevel = finding(ReviewArea.COMPLIANCE, Severity.MAJOR,
                "certificate scope is too narrow");

        assertFalse(skuDefect.matchedBy(vendorLevel),
                "a vendor-level finding does not answer a SKU-level defect");
    }
}
