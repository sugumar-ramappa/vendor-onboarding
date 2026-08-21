package com.learning.onboarding.intake;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * What could be read out of a document <b>without asking a model</b>.
 *
 * <p>This is the half of the work that should never be an LLM's job. Comparing
 * a date to another date, checking whether a standard appears in a list, testing
 * whether a cover amount clears a threshold - those are exact, cheap,
 * reproducible, and models are unreliable at them. Date arithmetic in particular
 * is a known weak spot: a model will confidently place April 2026 after August
 * 2026.
 *
 * <p>So the pipeline parses first and asks second. The compliance reviewer
 * receives these facts <em>alongside</em> the document text, and is asked only
 * the question that genuinely needs judgement: does the certificate's stated
 * scope cover the SKUs being applied for?
 *
 * <p>Every value carries the text it came from, so a finding built on one of
 * these can cite itself - and that citation is groundable by construction. See
 * {@link ParsedValue}.
 *
 * @param expiry       when the certificate stops being valid
 * @param issued       when it was issued
 * @param standards    standards referenced, e.g. "EN 62841"
 * @param amounts      monetary amounts, e.g. insurance cover
 * @param referenceIds certificate or policy numbers
 */
public record DocumentFacts(
        Optional<ParsedValue<LocalDate>> expiry,
        Optional<ParsedValue<LocalDate>> issued,
        List<ParsedValue<String>> standards,
        List<ParsedValue<BigDecimal>> amounts,
        List<ParsedValue<String>> referenceIds
) {

    public DocumentFacts {
        expiry = expiry == null ? Optional.empty() : expiry;
        issued = issued == null ? Optional.empty() : issued;
        standards = standards == null ? List.of() : List.copyOf(standards);
        amounts = amounts == null ? List.of() : List.copyOf(amounts);
        referenceIds = referenceIds == null ? List.of() : List.copyOf(referenceIds);
    }

    public static DocumentFacts none() {
        return new DocumentFacts(Optional.empty(), Optional.empty(),
                List.of(), List.of(), List.of());
    }

    /**
     * Has this certificate already expired by the given date?
     *
     * <p>Returns empty when no expiry could be parsed - which is a different
     * answer from "no". A document with no readable expiry needs a human or a
     * model to look at it, and reporting it as "not expired" would be exactly
     * the fail-open behaviour this project avoids.
     */
    public Optional<Boolean> expiredBy(LocalDate date) {
        return expiry.map(e -> e.value().isBefore(date));
    }

    /**
     * Does this document reference any of the standards the rule accepts?
     *
     * <p>Matching ignores case and internal spacing, because "EN 62841",
     * "EN62841" and "en 62841" are the same standard and a vendor's typesetting
     * is not a compliance failure.
     */
    public Optional<ParsedValue<String>> matching(List<String> acceptedStandards) {
        return standards.stream()
                .filter(found -> acceptedStandards.stream()
                        .anyMatch(accepted -> normalise(accepted).equals(normalise(found.value()))))
                .findFirst();
    }

    /** The largest amount found - insurance schedules list several. */
    public Optional<ParsedValue<BigDecimal>> largestAmount() {
        return amounts.stream().max((a, b) -> a.value().compareTo(b.value()));
    }

    private static String normalise(String s) {
        return s.replaceAll("[\\s-]", "").toUpperCase();
    }
}
