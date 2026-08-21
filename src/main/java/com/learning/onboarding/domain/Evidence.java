package com.learning.onboarding.domain;

/**
 * A citation: where in the submitted pack a finding came from.
 *
 * <p>Every finding must carry at least one of these, and the grounding guardrail
 * verifies that {@link #quote} actually appears in the document named by
 * {@link #documentId}. A finding whose citation does not check out is discarded
 * and the agent is re-prompted.
 *
 * <p><b>Why this is the most important guardrail in the project.</b> The worst
 * failure mode is not prompt injection - it is a confident, well-written finding
 * citing a clause that does not exist. A reviewer who checks two citations and
 * finds them fabricated stops trusting every finding the system will ever
 * produce, including the correct ones. Recovering that trust is far harder than
 * never losing it.
 *
 * @param documentId which submitted document, e.g. "iso9001-cert.pdf"
 * @param page       1-based page number; null for documents without pages
 * @param quote      verbatim text from the document, not a paraphrase
 */
public record Evidence(String documentId, Integer page, String quote) {

    public Evidence {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("evidence must name a document");
        }
        if (quote == null || quote.isBlank()) {
            throw new IllegalArgumentException(
                    "evidence must quote the source - a citation with no quote cannot be verified");
        }
        if (page != null && page < 1) {
            throw new IllegalArgumentException("page must be 1-based, got " + page);
        }
    }

    /** For documents with no meaningful pagination - forms, CSV, plain text. */
    public static Evidence of(String documentId, String quote) {
        return new Evidence(documentId, null, quote);
    }

    public String describe() {
        return page == null ? documentId : documentId + " p." + page;
    }
}
