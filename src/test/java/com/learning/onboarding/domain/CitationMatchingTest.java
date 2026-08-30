package com.learning.onboarding.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matching a cited quote against the document it names.
 *
 * <h2>Why these exist</h2>
 *
 * On 29 August the configuration 3 measurement scored 13 of 14 instead of 14,
 * and the missing defect had been found correctly. F20's logistics reviewer
 * spotted that a floodlight weighs 28.4 kg against a 25 kg manual handling
 * limit, cited the product-list row it read the weight from, and had the whole
 * finding discarded - because it rendered the row's double-space columns as
 * pipes and ended with an ellipsis.
 *
 * <p>The model had read a fixed-width table as a table, which is the correct
 * reading. A verbatim substring search called it a fabrication.
 *
 * <p><b>The guardrail still has to work.</b> An invented certificate clause must
 * still fail, because a reviewer who finds two citations invented stops trusting
 * every finding the system will ever produce. So the second nested class here
 * matters more than the first: it pins what must keep failing.
 */
class CitationMatchingTest {

    /** The exact row from F20, double spaces and all. */
    private static final SubmittedDocument PRODUCT_LIST = new SubmittedDocument(
            "product-list.pdf", DocumentType.PRODUCT_LIST,
            """
            PRODUCT LIST
            RAV-FLD-50  LED floodlight 50W IP65  GTIN 5033333000011  case pack 8  28.4kg
            RAV-BAT-04  Batten luminaire 4ft 40W  GTIN 6011111000028  case pack 6  11.2kg""",
            1);

    @Nested
    @DisplayName("a genuine citation survives being re-rendered")
    class Genuine {

        @Test
        @DisplayName("the quote that cost a real defect on F20")
        void theRegression() {
            assertThat(PRODUCT_LIST.contains(
                    "RAV-FLD-50 | LED floodlight 50W IP65 | GTIN 5033333000011 | ..."))
                    .isTrue();
        }

        @Test
        @DisplayName("pipes where the document has column spacing")
        void tableSeparators() {
            assertThat(PRODUCT_LIST.contains("RAV-BAT-04 | Batten luminaire 4ft 40W")).isTrue();
        }

        @Test
        @DisplayName("an ellipsis means 'and the rest', not a literal '...'")
        void elision() {
            // Both halves are present and in order; no document contains "...".
            assertThat(PRODUCT_LIST.contains("RAV-FLD-50 ... 28.4kg")).isTrue();
            assertThat(PRODUCT_LIST.contains("RAV-FLD-50 … 28.4kg")).isTrue();
        }

        @Test
        @DisplayName("line breaks and double spaces from PDF extraction")
        void whitespace() {
            assertThat(PRODUCT_LIST.contains("LED floodlight 50W IP65")).isTrue();
            assertThat(PRODUCT_LIST.contains("LED floodlight\n50W   IP65")).isTrue();
        }
    }

    @Nested
    @DisplayName("a fabricated citation still fails, which is the point")
    class Fabricated {

        @Test
        @DisplayName("text that is simply not there")
        void inventedText() {
            assertThat(PRODUCT_LIST.contains("RAV-FLD-50 is certified to EN 60598")).isFalse();
            assertThat(PRODUCT_LIST.mentionsInOrder("RAV-FLD-50 is certified to EN 60598"))
                    .isFalse();
        }

        @Test
        @DisplayName("a paraphrase is not a quote")
        void paraphrase() {
            // Every fact here is true of the document. None of the words are in
            // it. Grounding checks citations, not claims.
            assertThat(PRODUCT_LIST.contains(
                    "the floodlight is packed eight to a case weighing 28.4 kilograms"))
                    .isFalse();
        }

        @Test
        @DisplayName("a plausible figure that appears nowhere")
        void wrongNumber() {
            assertThat(PRODUCT_LIST.contains("RAV-FLD-50 | 31.6kg")).isFalse();
            assertThat(PRODUCT_LIST.mentionsInOrder("RAV-FLD-50 31.6kg")).isFalse();
        }

        @Test
        @DisplayName("separator normalisation does not let punctuation change a claim")
        void punctuationThatCarriesMeaning() {
            // Commas and full stops are deliberately NOT normalised: "28.4" and
            // "284" are different weights, and a clause can turn on a comma.
            assertThat(PRODUCT_LIST.contains("284kg")).isFalse();
        }
    }

    @Nested
    @DisplayName("mentionsInOrder separates 're-rendered' from 'invented'")
    class Anchoring {

        @Test
        @DisplayName("distinctive terms present and in order")
        void reRendered() {
            assertThat(PRODUCT_LIST.mentionsInOrder(
                    "RAV-FLD-50 -- LED floodlight, 50W IP65 -- 28.4kg")).isTrue();
        }

        @Test
        @DisplayName("right terms, wrong order, is not anchored")
        void orderMatters() {
            assertThat(PRODUCT_LIST.mentionsInOrder("28.4kg RAV-FLD-50")).isFalse();
        }

        @Test
        @DisplayName("terms from two different rows are not one citation")
        void acrossRows() {
            // Ordered and both present, so this passes - and is the reason
            // mentionsInOrder routes to a human instead of reporting verified.
            assertThat(PRODUCT_LIST.mentionsInOrder("RAV-FLD-50 11.2kg")).isTrue();
        }

        @Test
        @DisplayName("common words alone prove nothing")
        void noDistinctiveTerms() {
            assertThat(PRODUCT_LIST.mentionsInOrder("the and is")).isFalse();
        }
    }
}
