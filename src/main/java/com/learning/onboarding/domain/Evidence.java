package com.learning.onboarding.domain;

import java.io.Serializable;

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
public record Evidence(String documentId, Integer page, String quote) implements Serializable {

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

    /**
     * The constructor Jackson uses when reading MODEL OUTPUT.
     *
     * <h2>Why this is separate from the canonical constructor</h2>
     *
     * The invariant above is correct and stays strict for Java callers: a page
     * number below 1 is meaningless and code that produces one has a bug.
     *
     * Model output is not code. It is untrusted input, exactly like the vendor
     * documents this project is careful about, and it has to be sanitised at the
     * boundary rather than allowed to throw through it.
     *
     * <p><b>What happened without this.</b> A reviewer returned five valid
     * findings and one citation with {@code "page": 0}. Jackson constructed the
     * findings one by one, the sixth threw, and the exception propagated out of
     * deserialisation - so the ENTIRE review was recorded as failed and all five
     * good findings were lost. On a fixture carrying five planted defects that
     * turned one bad field into a reviewer that "did not run", which is the
     * worst outcome available: it is indistinguishable from a rate limit.
     *
     * <p>A model writing {@code page: 0} means "no meaningful page", not "the
     * zeroth page". Normalising it to null preserves what the citation is FOR -
     * the document id and the verbatim quote, both of which
     * {@code GroundingCheck} can still verify - and discards only the part that
     * was never usable.
     *
     * <p>The general rule this follows: <b>a malformed part of an answer should
     * cost you that part, not the answer.</b> The grounding check already works
     * this way for quotes it cannot find; deserialisation was simply reached
     * first.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static Evidence fromModel(
            @com.fasterxml.jackson.annotation.JsonProperty("documentId") String documentId,
            @com.fasterxml.jackson.annotation.JsonProperty("page") Integer page,
            @com.fasterxml.jackson.annotation.JsonProperty("quote") String quote) {
        return new Evidence(documentId, page != null && page < 1 ? null : page, quote);
    }

    /** For documents with no meaningful pagination - forms, CSV, plain text. */
    public static Evidence of(String documentId, String quote) {
        return new Evidence(documentId, null, quote);
    }

    public String describe() {
        return page == null ? documentId : documentId + " p." + page;
    }
}
