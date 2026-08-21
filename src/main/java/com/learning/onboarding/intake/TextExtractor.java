package com.learning.onboarding.intake;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a submitted file into text, page by page.
 *
 * <p>The first thing that touches vendor-supplied bytes, and therefore the first
 * place that has to be defensive. Everything downstream assumes it is handling
 * text; this is where that assumption is established or the document is
 * rejected.
 *
 * <h2>Limits, and why they are here rather than further in</h2>
 * A vendor uploads the files. Nothing stops them uploading a 2GB PDF with a
 * million pages, whether by accident or not, and by the time that reaches an
 * agent it has already exhausted memory. Bounds belong at the boundary.
 */
@Component
public class TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    private final long maxBytes;
    private final int maxPages;

    public TextExtractor(
            @Value("${onboarding.intake.max-file-bytes:20971520}") long maxBytes,
            @Value("${onboarding.intake.max-pages:200}") int maxPages) {
        this.maxBytes = maxBytes;
        this.maxPages = maxPages;
    }

    /**
     * @param content  the raw uploaded bytes
     * @param filename used only to choose a parser; never trusted as a fact
     * @throws IntakeException when the file cannot be turned into usable text
     */
    public ExtractedText extract(byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw new IntakeException(IntakeException.Cause.CORRUPT, filename, "empty file");
        }
        if (content.length > maxBytes) {
            throw new IntakeException(IntakeException.Cause.TOO_LARGE, filename,
                    "%d bytes exceeds limit of %d".formatted(content.length, maxBytes));
        }

        // Sniff the magic bytes rather than trusting the extension. A vendor
        // renaming a spreadsheet to .pdf is far more likely to be carelessness
        // than an attack, but either way the content decides, not the name.
        ExtractedText text = looksLikePdf(content)
                ? extractPdf(content, filename)
                : extractPlainText(content);

        if (text.hasNoTextLayer()) {
            // Almost always a scan. Reported, never treated as an empty
            // document - see ExtractedText.hasNoTextLayer().
            throw new IntakeException(IntakeException.Cause.NO_TEXT_LAYER, filename,
                    "%d page(s) parsed but no text found - probably a scan".formatted(text.pageCount()));
        }
        return text;
    }

    private static boolean looksLikePdf(byte[] content) {
        return content.length >= 5
                && content[0] == '%' && content[1] == 'P'
                && content[2] == 'D' && content[3] == 'F' && content[4] == '-';
    }

    private ExtractedText extractPdf(byte[] content, String filename) {
        try (PDDocument doc = Loader.loadPDF(content)) {

            if (doc.isEncrypted()) {
                // PDFBox can open some encrypted files with an empty password.
                // We refuse anyway: a document we had to work around to read is
                // one the vendor did not intend us to read as-is, and that is
                // worth a human deciding rather than a silent success.
                throw new IntakeException(IntakeException.Cause.ENCRYPTED, filename,
                        "password protected");
            }

            int total = doc.getNumberOfPages();
            int readable = Math.min(total, maxPages);
            if (total > maxPages) {
                log.warn("{}: {} pages, reading first {}", filename, total, maxPages);
            }

            PDFTextStripper stripper = new PDFTextStripper();
            // Keeps text in the order it appears visually. Without this, a
            // certificate laid out in columns extracts interleaved, which turns
            // "Valid until: 12 April 2026" into two unrelated fragments and
            // breaks label-based date parsing.
            stripper.setSortByPosition(true);

            List<String> pages = new ArrayList<>(readable);
            for (int p = 1; p <= readable; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(stripper.getText(doc));
            }
            return new ExtractedText(pages, total > maxPages);

        } catch (IOException e) {
            throw new IntakeException(IntakeException.Cause.CORRUPT, filename, e.getMessage());
        }
    }

    /**
     * Plain text and anything else UTF-8 decodable.
     *
     * <p>Split on form feeds, which is what plain-text exports use for a page
     * break. Most files have none and become a single page, which is correct -
     * a citation to "page 1" of a one-page document is honest.
     */
    private ExtractedText extractPlainText(byte[] content) {
        String all = new String(content, StandardCharsets.UTF_8);
        List<String> pages = List.of(all.split("\f"));
        return new ExtractedText(pages, false);
    }
}
