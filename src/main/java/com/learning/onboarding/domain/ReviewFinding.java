package com.learning.onboarding.domain;

import java.util.List;

/**
 * A finding as the system records it: what the model said, plus who asked and
 * how.
 *
 * <p>The split from {@link AgentFinding} is the point. An agent returns only
 * what it observed - severity, problem, evidence. The framework then stamps on the
 * two things an agent has no business asserting: which {@link ReviewArea} raised
 * it, decided by which node ran, and the {@link FindingSource} of the call.
 *
 * <pre>
 *   agent returns   ──►  AgentFinding    severity, problem, evidence
 *                             │
 *   framework adds  ──►  + area          which reviewer node ran
 *                        + source        correlation id, prompt version, model
 *                             │
 *                             ▼
 *                        ReviewFinding   what gets persisted and shown
 * </pre>
 *
 * <p>Two properties follow, and neither depends on the prompt behaving:
 * a finding cannot lie about which reviewer raised it, and every finding is
 * traceable to the exact call that produced it.
 *
 * @param findingId  stable identity, assigned by the framework
 * @param area  which reviewer - NOT model-supplied
 * @param details      what the agent actually returned
 * @param source which call produced it - NOT model-supplied
 * @param verdict    result of adversarial verification; null until verified
 */
public record ReviewFinding(
        String findingId,
        ReviewArea area,
        AgentFinding details,
        FindingSource source,
        Verdict verdict
) {

    public ReviewFinding {
        if (findingId == null || findingId.isBlank()) {
            throw new IllegalArgumentException("findingId is required");
        }
        if (area == null) {
            throw new IllegalArgumentException("area is required");
        }
        if (details == null) {
            throw new IllegalArgumentException("details is required");
        }
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        // verdict is intentionally nullable - findings exist before verification
    }

    /** Before verification has run. */
    public static ReviewFinding unverified(String findingId, ReviewArea area,
                                           AgentFinding details, FindingSource source) {
        return new ReviewFinding(findingId, area, details, source, null);
    }

    public ReviewFinding withVerdict(Verdict v) {
        return new ReviewFinding(findingId, area, details, source, v);
    }

    public Severity severity() {
        return details.severity();
    }

    public String problem() {
        return details.problem();
    }

    public List<Evidence> evidence() {
        return details.evidence();
    }

    /**
     * Whether this finding should reach a human.
     *
     * <p><b>Unverified findings count as surviving.</b> That is deliberate: if
     * verification did not run - the model was unavailable, the budget was
     * exhausted - the finding must still be shown. Dropping it would mean a
     * failure in the verifier silently suppresses a real problem, which is the
     * fail-open behaviour this project exists to avoid.
     */
    public boolean survives() {
        return verdict == null || !verdict.disproved();
    }
}
