package com.learning.onboarding.graph;

import com.learning.onboarding.domain.Conflict;
import com.learning.onboarding.domain.Evidence;
import com.learning.onboarding.domain.ReviewFinding;
import com.learning.onboarding.domain.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds where two reviewers disagreed about the same thing.
 *
 * <p><b>No model involved.</b> Comparing severities on a shared subject is
 * arithmetic, and doing it in Java makes it exact, free, and reproducible - which
 * matters because this runs on every application and its output goes in front of
 * a human as a claim that two reviewers contradict each other.
 *
 * <h2>What counts as disagreement</h2>
 * Not any difference. Two reviewers reporting MINOR and MAJOR on the same SKU are
 * broadly agreeing; reporting INFO and BLOCKING are not. The gap has to be
 * material, and {@link #MATERIAL_GAP} is where that line sits.
 *
 * <p>Findings from the <em>same</em> area are never a conflict. One reviewer
 * raising several findings of differing severity is a reviewer doing its job,
 * not a contradiction.
 *
 * <h2>Why SKU disputes outrank document disputes</h2>
 * Two reviewers disagreeing about one SKU are looking at the same item and one is
 * wrong. Two reviewers citing the same document may simply be reading it for
 * different things - the compliance reviewer for scope, the finance reviewer for
 * an amount - so a difference there is weaker evidence and is only reported when
 * the severities are far apart AND no SKU dispute already covers the pair.
 */
@Component
public class ConflictDetector {

    /**
     * How far apart two severities must be to count as a disagreement.
     *
     * <p>2 means INFO vs MAJOR, MINOR vs BLOCKING, or wider. Adjacent severities
     * - MAJOR and BLOCKING - are two reviewers weighting the same problem
     * slightly differently, which is not worth a human's time.
     */
    public static final int MATERIAL_GAP = 2;

    public List<Conflict> detect(List<ReviewFinding> findings) {
        List<Conflict> conflicts = new ArrayList<>();
        Set<String> pairsAlreadyReported = new java.util.HashSet<>();

        // SKU disputes first, so a pair reported here is not reported again as
        // the weaker document dispute.
        for (int i = 0; i < findings.size(); i++) {
            for (int j = i + 1; j < findings.size(); j++) {
                ReviewFinding a = findings.get(i);
                ReviewFinding b = findings.get(j);

                if (!disagree(a, b)) {
                    continue;
                }
                if (a.details().isSkuSpecific()
                        && a.details().skuRef().equals(b.details().skuRef())) {
                    conflicts.add(new Conflict.SkuDispute(a, b, a.details().skuRef()));
                    pairsAlreadyReported.add(pairKey(a, b));
                }
            }
        }

        for (int i = 0; i < findings.size(); i++) {
            for (int j = i + 1; j < findings.size(); j++) {
                ReviewFinding a = findings.get(i);
                ReviewFinding b = findings.get(j);

                if (!disagree(a, b) || pairsAlreadyReported.contains(pairKey(a, b))) {
                    continue;
                }
                sharedDocument(a, b).ifPresent(documentId ->
                        conflicts.add(new Conflict.DocumentDispute(a, b, documentId)));
            }
        }
        return conflicts;
    }

    /**
     * Two findings disagree when they come from different reviewers and their
     * severities are materially apart.
     */
    private boolean disagree(ReviewFinding a, ReviewFinding b) {
        if (a.area() == b.area()) {
            // One reviewer raising findings of differing severity is doing its
            // job. Only cross-area disagreement is a contradiction.
            return false;
        }
        return severityGap(a.severity(), b.severity()) >= MATERIAL_GAP;
    }

    private int severityGap(Severity a, Severity b) {
        return Math.abs(a.ordinal() - b.ordinal());
    }

    private java.util.Optional<String> sharedDocument(ReviewFinding a, ReviewFinding b) {
        Set<String> aDocs = a.evidence().stream()
                .map(Evidence::documentId).collect(Collectors.toSet());
        return b.evidence().stream()
                .map(Evidence::documentId)
                .filter(aDocs::contains)
                .findFirst();
    }

    /** Order-independent, so a pair is only ever reported once. */
    private String pairKey(ReviewFinding a, ReviewFinding b) {
        return a.findingId().compareTo(b.findingId()) < 0
                ? a.findingId() + "|" + b.findingId()
                : b.findingId() + "|" + a.findingId();
    }
}
