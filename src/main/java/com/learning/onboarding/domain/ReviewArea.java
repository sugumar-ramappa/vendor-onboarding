package com.learning.onboarding.domain;

/**
 * Which reviewer produced a finding - one constant per department that reviews
 * a supplier application today.
 *
 * <p>Deliberately NOT part of what a model returns. The area is determined
 * by which graph node ran, and is stamped on by the framework afterwards. A
 * compliance reviewer that could label its own output {@code FINANCE} would
 * make the per-area measurement meaningless, and the conflict detector -
 * which works by comparing findings across dimensions - would be comparing
 * whatever the model felt like claiming.
 *
 * <p>This is the same principle as the missing {@code approved} field on
 * {@link AgentFinding}: authority that the type cannot express is authority no
 * prompt can acquire.
 */
public enum ReviewArea {

    /** Is the submission pack complete for this product category? */
    COMPLETENESS,

    /** Certificate validity, scope, expiry, issuing body, insurance territory. */
    COMPLIANCE,

    /** Audit reports, non-conformances, corrective actions. */
    QUALITY,

    /** EDI readiness, lead times, geographic coverage, capacity. */
    LOGISTICS,

    /** Filing history, credit indicators, requested payment terms. */
    FINANCE
}
