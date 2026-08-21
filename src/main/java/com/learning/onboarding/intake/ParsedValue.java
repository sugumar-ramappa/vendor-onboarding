package com.learning.onboarding.intake;

import com.learning.onboarding.domain.Evidence;

/**
 * A value pulled out of a document, together with the text it came from.
 *
 * <p>The {@link #sourceQuote} is the point. A parser that returns
 * {@code LocalDate.of(2026, 4, 12)} and nothing else leaves a reviewer with no
 * way to check it - they have to go and find the date themselves, and at that
 * point the parse saved them nothing.
 *
 * <p>Carrying the quote means a deterministic finding can produce its own
 * citation, and that citation is <b>guaranteed to be groundable</b> because it
 * is literally the substring the value was read from. Compare that with a model
 * asked to quote its source, which may paraphrase, may cite the wrong line, or
 * may invent one.
 *
 * @param value       what was parsed
 * @param sourceQuote the exact text it came from
 * @param page        which page, or null for documents without pages
 */
public record ParsedValue<T>(T value, String sourceQuote, Integer page) {

    public ParsedValue {
        if (value == null) {
            throw new IllegalArgumentException("value is required");
        }
        if (sourceQuote == null || sourceQuote.isBlank()) {
            throw new IllegalArgumentException(
                    "sourceQuote is required - a parsed value that cannot show its "
                            + "own source cannot be cited, and an uncitable finding is discarded");
        }
    }

    /** Turns this into a citation. Always groundable, by construction. */
    public Evidence asEvidence(String documentId) {
        return new Evidence(documentId, page, sourceQuote);
    }
}
