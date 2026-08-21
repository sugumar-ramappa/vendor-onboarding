package com.learning.onboarding.domain;

import java.io.Serializable;

/**
 * Two reviewers looking at the same thing and disagreeing about it.
 *
 * <p><b>A conflict is never resolved automatically.</b> It is surfaced with both
 * positions and both pieces of evidence, because the resolution is a business
 * decision rather than a factual one - and because at least one of the two
 * findings is wrong, which is exactly what a human needs to see.
 *
 * <p>This is what a single reviewer cannot produce. One agent covering all five
 * areas would notice the tension internally, resolve it, and emit a paragraph
 * that reads as a settled conclusion. The disagreement - the most informative
 * thing in the review - disappears into the averaging.
 *
 * <h2>What conflict detection can and cannot see</h2>
 * Honest scope: this detects reviewers disagreeing about the <em>same subject</em>,
 * identified by SKU or by cited document. It does not detect logically
 * incompatible claims in general - "the case pack should be 12" versus "the case
 * pack should be 6" phrased in free text would need a model to adjudicate.
 *
 * <p>The narrow version is deterministic, cheap and precise, which is the right
 * trade for something that runs on every application. A model-adjudicated pass
 * over the candidates it finds is a reasonable extension.
 */
public sealed interface Conflict extends Serializable {

    ReviewFinding first();

    ReviewFinding second();

    /** What the two findings disagree about, for the review pack. */
    String subject();

    String describe();

    /**
     * Two reviewers reached materially different severities about the same SKU.
     *
     * <p>The interesting case in practice: logistics reports a SKU as a minor
     * packaging note while compliance reports the same SKU as outside the
     * certificate scope. Both are looking at the same item and one of them has
     * badly misjudged it.
     */
    record SkuDispute(ReviewFinding first, ReviewFinding second, String sku)
            implements Conflict {

        @Override
        public String subject() {
            return "SKU " + sku;
        }

        @Override
        public String describe() {
            return "%s says %s (%s); %s says %s (%s)".formatted(
                    first.area(), first.severity(), first.problem(),
                    second.area(), second.severity(), second.problem());
        }
    }

    /**
     * Two reviewers cited the same document and reached materially different
     * severities.
     *
     * <p>Weaker evidence of disagreement than a SKU dispute - two reviewers can
     * legitimately draw different conclusions from one document, because they
     * are looking for different things in it. Reported at lower prominence, and
     * only when the severities are far apart.
     */
    record DocumentDispute(ReviewFinding first, ReviewFinding second, String documentId)
            implements Conflict {

        @Override
        public String subject() {
            return "document " + documentId;
        }

        @Override
        public String describe() {
            return "%s says %s; %s says %s - both citing %s".formatted(
                    first.area(), first.severity(),
                    second.area(), second.severity(), documentId);
        }
    }
}
