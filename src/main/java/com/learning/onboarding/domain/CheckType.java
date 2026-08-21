package com.learning.onboarding.domain;

/**
 * How a finding was reached: by arithmetic, or by judgement.
 *
 * <p>This distinction turned out to matter more than expected. A great deal of
 * compliance checking is deterministic once the fields are extracted - is
 * {@code EN 62841} in the accepted-standards list, is the expiry after the
 * requested go-live date, is the case weight under the manual-handling limit.
 * Those are set membership and comparisons, and they belong in Java where they
 * are exact and testable.
 *
 * <p>What genuinely needs a model is the other kind:
 *
 * <pre>
 *   Certificate scope: "hand tools and non-powered garden implements"
 *   Applying to supply: "18V cordless drill, 2Ah battery, 2-pack"
 *   → is the certified scope sufficient?
 * </pre>
 *
 * <p>No string comparison answers that.
 *
 * <p><b>Why it is recorded on every finding.</b> A reviewer reading the output
 * needs to know which findings are arithmetic and which are opinion. The
 * SEMANTIC ones are where to spend verification effort, and where the
 * adversarial verifier earns its keep. Telling them apart is a feature, not
 * bookkeeping.
 */
public enum CheckType {

    /**
     * Decided in Java from extracted values - dates, thresholds, set membership.
     * Reproducible, and identical on every run.
     */
    DETERMINISTIC,

    /**
     * Decided by a model weighing meaning. May vary between runs, and is the
     * category worth double-checking.
     */
    SEMANTIC
}
