package com.learning.onboarding.intake;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The deterministic half of document intake.
 *
 * <p>No Spring, no model, no database. These run in milliseconds, which is what
 * lets them be thorough - the equivalent checks done by a model would cost a
 * network call each and could not be asserted on exactly.
 */
class FactExtractorTest {

    private final FactExtractor extractor = new FactExtractor();

    @Nested
    @DisplayName("expiry dates")
    class Expiry {

        @Test
        @DisplayName("reads the formats certificates actually use")
        void parsesRealFormats() {
            record Case(String text, LocalDate expected) {}

            for (Case c : List.of(
                    new Case("Valid until: 12 April 2026", LocalDate.of(2026, 4, 12)),
                    new Case("Valid until 12 Apr 2026", LocalDate.of(2026, 4, 12)),
                    new Case("Expiry date: 2026-04-12", LocalDate.of(2026, 4, 12)),
                    new Case("Expires on 12/04/2026", LocalDate.of(2026, 4, 12)),
                    new Case("Valid to: April 12, 2026", LocalDate.of(2026, 4, 12)))) {

                var facts = extractor.extract(c.text(), 1);
                assertTrue(facts.expiry().isPresent(), "no expiry found in: " + c.text());
                assertEquals(c.expected(), facts.expiry().get().value(), "wrong date for: " + c.text());
            }
        }

        @Test
        @DisplayName("picks the expiry, not just the first date on the page")
        void distinguishesExpiryFromIssue() {
            // A real certificate carries several dates. Matching the LABEL is
            // what stops the issue date being reported as the expiry.
            String text = """
                    Certificate of Conformity
                    Date of issue: 3 March 2024
                    Audit completed: 28 February 2024
                    Valid until: 12 April 2026
                    """;

            var facts = extractor.extract(text, 1);
            assertEquals(LocalDate.of(2026, 4, 12), facts.expiry().orElseThrow().value());
            assertEquals(LocalDate.of(2024, 3, 3), facts.issued().orElseThrow().value());
        }

        @Test
        @DisplayName("reports nothing rather than guessing")
        void unparseableDateIsAbsent() {
            var facts = extractor.extract("Valid until: sometime next spring", 1);
            assertTrue(facts.expiry().isEmpty(),
                    "an unparseable date must be absent, not guessed - a wrong "
                            + "deterministic finding looks trustworthy and is not");
        }

        @Test
        @DisplayName("expiredBy is empty when no date was found, not false")
        void unknownExpiryIsNotTreatedAsValid() {
            var facts = extractor.extract("Certificate of Conformity", 1);
            assertTrue(facts.expiredBy(LocalDate.of(2026, 8, 21)).isEmpty(),
                    "reporting an unknown expiry as 'not expired' is fail-open");
        }

        @Test
        @DisplayName("the citation shows which date it was")
        void quoteIncludesTheLabel() {
            var facts = extractor.extract("Valid until: 12 April 2026", 1);
            String quote = facts.expiry().orElseThrow().sourceQuote();

            assertTrue(quote.toLowerCase().contains("valid until"),
                    "a quote of just the date proves nothing about which date it is: " + quote);
            assertTrue(quote.contains("12 April 2026"));
        }
    }

    @Nested
    @DisplayName("standards")
    class Standards {

        @Test
        @DisplayName("finds standards in the forms vendors write them")
        void findsStandards() {
            String text = "Tested to EN 62841 and IEC62133. Also complies with BS 7671 and EN 13501-1.";
            var found = extractor.extract(text, 1).standards().stream()
                    .map(ParsedValue::value).toList();

            assertTrue(found.contains("EN 62841"));
            assertTrue(found.contains("IEC 62133"), "spacing varies: 'IEC62133' is the same standard");
            assertTrue(found.contains("BS 7671"));
            assertTrue(found.contains("EN 13501-1"), "sub-parts matter: 13501-1 is not 13501");
        }

        @Test
        @DisplayName("matches the accepted list regardless of spacing or case")
        void matchesAcceptedList() {
            var facts = extractor.extract("Certified to en62841 rev 2", 1);
            var match = facts.matching(List.of("EN 62841", "IEC 62841"));

            assertTrue(match.isPresent(), "vendor typesetting is not a compliance failure");
            assertEquals("EN 62841", match.get().value());
        }

        @Test
        @DisplayName("no match when the standard is genuinely different")
        void doesNotMatchDifferentStandard() {
            var facts = extractor.extract("Certified to EN 60745", 1);
            assertTrue(facts.matching(List.of("EN 62841")).isEmpty());
        }
    }

    @Nested
    @DisplayName("amounts")
    class Amounts {

        @Test
        @DisplayName("reads currency in the forms insurance schedules use")
        void parsesAmounts() {
            var facts = extractor.extract(
                    "Public liability: £5,000,000. Product liability: GBP 2m. Excess: £500", 1);
            var values = facts.amounts().stream().map(ParsedValue::value).toList();

            assertTrue(values.contains(new BigDecimal("5000000")));
            assertTrue(values.contains(new BigDecimal("2000000")), "'2m' means two million");
            assertTrue(values.contains(new BigDecimal("500")));
        }

        @Test
        @DisplayName("the largest amount is the cover, not the excess")
        void largestAmountWins() {
            var facts = extractor.extract("Cover £5,000,000 with an excess of £500", 1);
            assertEquals(new BigDecimal("5000000"), facts.largestAmount().orElseThrow().value());
        }
    }

    @Nested
    @DisplayName("citations")
    class Citations {

        @Test
        @DisplayName("every parsed value can produce groundable evidence")
        void everyValueCitesItself() {
            String text = "Valid until: 12 April 2026. Tested to EN 62841.";
            var facts = extractor.extract(text, 3);

            var evidence = facts.expiry().orElseThrow().asEvidence("cert.pdf");

            assertEquals("cert.pdf", evidence.documentId());
            assertEquals(3, evidence.page());
            // Groundable by construction: the quote is a substring of the text
            // it was read from, so the grounding check cannot fail on it.
            assertTrue(text.contains(evidence.quote()),
                    "quote is not a substring of the source: " + evidence.quote());
        }
    }
}
