package com.learning.onboarding.domain;

import java.io.Serializable;
import java.util.List;

/**
 * The adversarial verifier's answer.
 *
 * <p>A separate agent is asked to <em>refute</em> the finding, with its own
 * context and its own prompt. A model asked to check work it just produced
 * agrees with itself; one that did not write the finding has no such attachment.
 *
 * <p>This is the biggest single lever on false positives, and false positives
 * decide whether a review tool gets used or muted.
 *
 * <h2>Three outcomes, not two</h2>
 * "Could not decide" is a real answer and collapsing it into either of the
 * others loses information the graph needs:
 *
 * <ul>
 *   <li>fold it into SURVIVES and a finding that one more lookup would have
 *       settled goes to a human unnecessarily</li>
 *   <li>fold it into DISPROVED and a genuine problem is dropped because the
 *       verifier was unsure - which is the fail-open failure this project
 *       exists to avoid</li>
 * </ul>
 *
 * <p>Kept separate, UNRESOLVED becomes a routing decision: the graph sends the
 * finding round again with the specific evidence the verifier asked for, up to a
 * bound, and only then gives up.
 *
 * @param outcome          survived, disproved, or could not decide
 * @param reason           why - shown to a human, so it must stand on its own
 * @param evidence         citations supporting a refutation
 * @param needsEvidenceFor for UNRESOLVED: the specific thing that would settle
 *                         it. Not "more information" but "whether policy
 *                         PL-4471029 has a territorial endorsement". Prose, for
 *                         a human reading the audit trail
 * @param needs            for UNRESOLVED: which reference data to fetch, from a
 *                         closed set. This is what actually drives the lookup -
 *                         {@code needsEvidenceFor} explains, {@code needs} acts.
 *                         Empty means the verifier could not say what would help,
 *                         so another pass cannot either
 */
public record Verdict(
        Outcome outcome,
        String reason,
        List<Evidence> evidence,
        String needsEvidenceFor,
        List<EvidenceNeed> needs
) implements Serializable {

    public enum Outcome {
        /** The challenge failed. The finding stands. */
        SURVIVES,
        /** The verifier showed the finding is wrong, with evidence. */
        DISPROVED,
        /**
         * Could not decide with what was available.
         *
         * <p>Never treated as a refutation. The finding still stands if this is
         * where it ends up after the retry bound - a dropped finding means a
         * non-compliant vendor is approved, and a surviving false positive costs
         * a human five minutes.
         */
        UNRESOLVED
    }

    public Verdict {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "a verdict must explain itself - an unexplained refutation is "
                            + "indistinguishable from the verifier malfunctioning");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        needs = needs == null ? List.of() : List.copyOf(needs);
    }

    public static Verdict survives(String reason) {
        return new Verdict(Outcome.SURVIVES, reason, List.of(), null, List.of());
    }

    public static Verdict disproved(String reason, List<Evidence> evidence) {
        return new Verdict(Outcome.DISPROVED, reason, evidence, null, List.of());
    }

    /** Undecided, and the verifier named what to fetch. Another pass can help. */
    public static Verdict unresolved(String reason, String needsEvidenceFor,
                                     List<EvidenceNeed> needs) {
        return new Verdict(Outcome.UNRESOLVED, reason, List.of(), needsEvidenceFor, needs);
    }

    /**
     * Undecided, and the verifier could not say what would settle it.
     *
     * <p>Distinct from the three-argument form on purpose: with nothing named,
     * there is nothing to fetch, so the graph does not spend a pass finding that
     * out. The finding survives and goes to a human.
     */
    public static Verdict unresolved(String reason, String needsEvidenceFor) {
        return new Verdict(Outcome.UNRESOLVED, reason, List.of(), needsEvidenceFor, List.of());
    }

    /** True only when the verifier positively showed the finding is wrong. */
    public boolean disproved() {
        return outcome == Outcome.DISPROVED;
    }

    /**
     * Whether another pass could plausibly settle this.
     *
     * <p>Requires a named {@link EvidenceNeed}, not just prose. A verifier that
     * says "I need more information" without saying which lookup would provide
     * it has described its own uncertainty, not a plan - and sending that round
     * the cycle costs a model call to arrive at the same place.
     */
    public boolean needsAnotherPass() {
        return outcome == Outcome.UNRESOLVED && !needs.isEmpty();
    }
}
