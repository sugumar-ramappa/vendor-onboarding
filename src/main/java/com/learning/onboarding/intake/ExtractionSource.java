package com.learning.onboarding.intake;

/**
 * How a piece of text was obtained from a document.
 *
 * <p>This travels with every parsed value, and it changes what may be concluded
 * from it. The reason is worth stating plainly, because it is easy to miss:
 *
 * <pre>
 *   PDF text layer  →  regex  →  2026-04-12  →  compare to today  →  EXACT
 *   scanned image   →  MODEL  →  2026-04-12  →  compare to today  →  NOT EXACT
 * </pre>
 *
 * <p>The comparison is exact in both cases. The <em>input</em> is not. A model
 * reading a stamped, skewed scan can turn 2026 into 2020, or a smudged 1 into a
 * 7 - and the resulting finding would look every bit as confident as one read
 * from a text layer.
 *
 * <p>So provenance is carried, not discarded, and three rules follow from it:
 *
 * <ol>
 *   <li>a value from {@link #MODEL_VISION} may not produce a
 *       {@code DETERMINISTIC} finding</li>
 *   <li>its evidence cannot be grounded by substring match - there is no source
 *       text to match against</li>
 *   <li>anything BLOCKING built on one goes to a human regardless of what the
 *       verifier says</li>
 * </ol>
 *
 * <p>That last rule is the important one. <i>"We blocked this vendor because a
 * model thought a smudged certificate said 2020"</i> is not a decision to take
 * automatically.
 */
public enum ExtractionSource {

    /**
     * Read from a PDF text layer, a plain-text file, or a spreadsheet cell.
     * Byte-for-byte what the document contains. Reproducible.
     */
    NATIVE_TEXT,

    /**
     * Read from an image by a multimodal model, because the document had no
     * text layer - a scan.
     *
     * <p>Supported because scanned certificates are normal, particularly from
     * smaller vendors who scan a stamped and signed original. Refusing them
     * would make the system unusable rather than safe.
     */
    MODEL_VISION;

    /** Can a finding built on this claim to be arithmetic rather than judgement? */
    public boolean supportsDeterministicFindings() {
        return this == NATIVE_TEXT;
    }

    /** Can an evidence quote be verified by searching the source text? */
    public boolean supportsGroundingByQuote() {
        return this == NATIVE_TEXT;
    }

    /** Must a blocking finding built on this be seen by a human? */
    public boolean requiresHumanReviewWhenBlocking() {
        return this == MODEL_VISION;
    }
}
