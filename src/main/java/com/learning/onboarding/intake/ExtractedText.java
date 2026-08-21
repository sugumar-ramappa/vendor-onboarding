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
 * @param pages     text per page, in order; one entry for a page-less format
 * @param truncated whether extraction stopped early at the page limit
 * @param source    whether this text was read exactly or transcribed from an
 *                  image by a model - see {@link ExtractionSource}, which
 *                  governs what may be concluded from it
 */
public record ExtractedText(List<String> pages, boolean truncated, ExtractionSource source) {

    public ExtractedText {
        pages = pages == null ? List.of() : List.copyOf(pages);
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
    }

    public static ExtractedText exact(List<String> pages, boolean truncated) {
        return new ExtractedText(pages, truncated, ExtractionSource.NATIVE_TEXT);
    }

    public static ExtractedText fromVision(List<String> pages, boolean truncated) {
        return new ExtractedText(pages, truncated, ExtractionSource.MODEL_VISION);
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
            throw new IndexOutOfBoundsException("page " + number + " of " + pages.size());
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
     * <p>So it is detected explicitly, and the page is either transcribed by a
     * multimodal model or the document is rejected. Never passed on as empty.
     */
    public boolean hasNoTextLayer() {
        return pages.stream().allMatch(p -> p == null || p.isBlank());
    }

    /**
     * Whether findings built on this text may be reported as arithmetic.
     *
     * <p>False for a transcription: the date comparison is still exact, but the
     * date came from a model reading a possibly-smudged image, so the finding as
     * a whole is judgement rather than calculation.
     */
    public boolean supportsDeterministicFindings() {
        return source.supportsDeterministicFindings();
    }
}
