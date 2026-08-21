package com.learning.onboarding.intake;

import java.util.List;

/**
 * Text pulled out of a submitted file, page by page.
 *
 * <p>Pages are kept separate rather than concatenated because citations need a
 * page number. "It says so somewhere in this 40-page audit report" is not a
 * citation a reviewer can check in five seconds, and a citation nobody checks
 * is a citation nobody trusts.
 *
 * @param pages    text per page, in order; one entry for a page-less format
 * @param truncated whether extraction stopped early at the page limit
 */
public record ExtractedText(List<String> pages, boolean truncated) {

    public ExtractedText {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public int pageCount() {
        return pages.size();
    }

    /** Everything joined, for checks that do not care about page boundaries. */
    public String fullText() {
        return String.join("\n", pages);
    }

    /** Page numbers are 1-based, as they are in every citation and PDF reader. */
    public String page(int number) {
        if (number < 1 || number > pages.size()) {
            throw new IndexOutOfBoundsException(
                    "page " + number + " of " + pages.size());
        }
        return pages.get(number - 1);
    }

    /**
     * True when the file parsed but yielded no usable text.
     *
     * <p>The case this exists for: <b>a scanned certificate</b>. The PDF is
     * valid, has pages, opens fine in a viewer - and contains images of text,
     * not text. Extraction returns empty strings.
     *
     * <p>Treating that as "an empty document" would let a scanned certificate
     * sail through completeness (the file is present) and produce no compliance
     * findings (there is nothing to find). The vendor appears compliant because
     * their certificate was unreadable, which is precisely backwards.
     *
     * <p>So this is checked explicitly and escalates to a human. Fail closed.
     */
    public boolean hasNoTextLayer() {
        return pages.stream().allMatch(p -> p == null || p.isBlank());
    }
}
