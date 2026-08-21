package com.learning.onboarding.domain;

/**
 * How serious a finding is.
 *
 * <p>Declared in ascending order so {@link #compareTo} is meaningful and the
 * decision gate can ask {@code severity.atLeast(BLOCKING)} rather than
 * enumerating cases. Reordering these constants changes behaviour - the order
 * is load-bearing, not cosmetic.
 *
 * <p>An enum rather than a String or an int: Spring AI puts the permitted values
 * into the JSON schema it sends, so the model chooses from this list instead of
 * inventing "CRITICAL" or "severity 7". A whole class of output validation
 * disappears into the type.
 */
public enum Severity {

    /** Worth recording, affects nothing. */
    INFO,

    /** A gap the vendor should close, but onboarding can proceed. */
    MINOR,

    /** Needs resolution before onboarding, but a human may waive it. */
    MAJOR,

    /**
     * Onboarding cannot proceed.
     *
     * <p>Note that only the decision gate acts on this. A reviewer returning
     * BLOCKING is stating a fact about the evidence, not making a decision -
     * whether it actually blocks is read from configuration in Java.
     */
    BLOCKING;

    /** True if this severity is at least as serious as {@code other}. */
    public boolean atLeast(Severity other) {
        return this.compareTo(other) >= 0;
    }
}
