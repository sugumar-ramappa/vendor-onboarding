package com.learning.onboarding.domain;

import java.util.List;

/**
 * The adversarial verifier's answer: does this finding survive an attempt to
 * refute it?
 *
 * <p>The verifier is a separate agent, with a separate context, told to argue
 * <em>against</em> the finding and to default to disproved when uncertain. A model
 * asked to check its own work agrees with itself; a model asked to attack a
 * finding it did not write does not.
 *
 * <p>This is the single biggest lever on false positives, which is what
 * determines whether a review tool gets used or muted. A tool that raises eleven
 * findings of which six are wrong gets switched off, and the five real ones go
 * with it.
 *
 * @param disproved   true if the verifier successfully argued the finding away
 * @param reason why - shown to the reviewer, so it must stand on its own
 * @param evidence  citations supporting the refutation; same grounding rule
 */
public record Verdict(
        boolean disproved,
        String reason,
        List<Evidence> evidence
) {

    public Verdict {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "a verdict must explain itself - an unexplained refutation is "
                            + "indistinguishable from the verifier malfunctioning");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static Verdict survives(String reason) {
        return new Verdict(false, reason, List.of());
    }

    public static Verdict disproved(String reason, List<Evidence> evidence) {
        return new Verdict(true, reason, evidence);
    }
}
