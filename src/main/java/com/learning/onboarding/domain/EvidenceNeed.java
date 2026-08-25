package com.learning.onboarding.domain;

/**
 * What a verifier may ask to have fetched for it.
 *
 * <p>A closed set, on purpose. The verifier also says in prose what it needs -
 * "whether policy PL-4471029 has a territorial endorsement" - and that prose is
 * useful to a human reading the audit trail. It is useless for deciding which
 * query to run.
 *
 * <h2>Why not just parse the sentence</h2>
 * Two options, both worse:
 *
 * <ul>
 *   <li><b>Keyword matching.</b> "endorsement" is not in any column name. The
 *       matching would be a pile of synonyms that silently fetches the wrong
 *       table whenever the model phrases something new.</li>
 *   <li><b>A model call to route it.</b> An extra call per pass, an extra
 *       failure mode, and - the real objection - a path where text originating
 *       in a vendor's PDF influences which query runs.</li>
 * </ul>
 *
 * <p>An enum has neither problem. The model picks a name or the response fails
 * to bind, and there is no phrasing that turns into a query nobody intended.
 *
 * <p>It also makes the fetch narrow enough to justify itself: one table rather
 * than the whole rulebook, which is the difference between "we fetched what it
 * asked for" and "we fetched everything and hoped".
 */
public enum EvidenceNeed {

    /** Which documents are mandatory for this category and delivery model. */
    REQUIRED_DOCUMENTS(
            "which documents are mandatory for this product category and delivery model"),

    /** Accepted safety and conformity standards for the category. */
    COMPLIANCE_RULES(
            "which safety and conformity standards are accepted for this product category"),

    /** ASN, GS1, labelling and lead-time rules for the delivery model. */
    LOGISTICS_REQUIREMENTS(
            "ASN, GS1 registration, labelling and lead-time rules for this delivery model"),

    /** Insurance cover levels, credit terms and other finance thresholds. */
    FINANCE_THRESHOLDS(
            "required insurance cover, credit terms and other finance thresholds");

    private final String description;

    EvidenceNeed(String description) {
        this.description = description;
    }

    /**
     * Shown to the model as the menu it chooses from.
     *
     * <p>Generated from the enum rather than written into the prompt file, so a
     * new value cannot be added without the model being told it exists.
     */
    public String description() {
        return description;
    }

    public static String menu() {
        var sb = new StringBuilder();
        for (EvidenceNeed need : values()) {
            sb.append("  ").append(need.name()).append(" - ").append(need.description).append('\n');
        }
        return sb.toString();
    }
}
