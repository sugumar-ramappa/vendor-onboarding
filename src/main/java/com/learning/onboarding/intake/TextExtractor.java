package com.learning.onboarding.intake;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
 * <h2>Two routes, and the difference matters downstream</h2>
 * <pre>
 *   PDF with a text layer  →  PDFBox        →  NATIVE_TEXT   exact
 *   plain text, CSV        →  UTF-8 decode  →  NATIVE_TEXT   exact
 *   scanned PDF (images)   →  vision model  →  MODEL_VISION  probabilistic
 * </pre>
 *
 * <p>The third route exists because scanned certificates are normal, especially
 * from smaller vendors sending a stamped and signed original. What comes back is
 * tagged so nothing downstream mistakes it for an exact reading.
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
    private final int maxVisionPages;
    private final int visionDpi;
    private final ScannedPageReader visionReader;

    @Autowired   // three constructors exist; this is the one Spring must use
    public TextExtractor(
            @Value("${onboarding.intake.max-file-bytes:20971520}") long maxBytes,
            @Value("${onboarding.intake.max-pages:200}") int maxPages,
            // Far lower than maxPages: every vision page is a model call, so an
            // 80-page scanned audit report would be 80 calls and a large bill.
            // Beyond this a human is cheaper and more reliable.
            @Value("${onboarding.intake.vision.max-pages:15}") int maxVisionPages,
            // 200 DPI reads stamped and signed certificates reliably. Higher
            // mostly grows the image without improving the transcription.
            @Value("${onboarding.intake.vision.dpi:200}") int visionDpi,
            ObjectProvider<ScannedPageReader> visionReader) {
        this.maxBytes = maxBytes;
        this.maxPages = maxPages;
        this.maxVisionPages = maxVisionPages;
        this.visionDpi = visionDpi;
        // Absent when vision is switched off, in which case scans are rejected.
        this.visionReader = visionReader.getIfAvailable(() -> ScannedPageReader.UNAVAILABLE);
    }

    /** Test constructor: no Spring, no vision. */
    public TextExtractor(long maxBytes, int maxPages) {
        this(maxBytes, maxPages, 15, 200, ScannedPageReader.UNAVAILABLE);
    }

    /** Test constructor with a stub reader. */
    public TextExtractor(long maxBytes, int maxPages, int maxVisionPages, int visionDpi,
                         ScannedPageReader visionReader) {
        this.maxBytes = maxBytes;
        this.maxPages = maxPages;
        this.maxVisionPages = maxVisionPages;
        this.visionDpi = visionDpi;
        this.visionReader = visionReader;
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
        return looksLikePdf(content)
                ? extractPdf(content, filename)
                : extractPlainText(content);
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

            ExtractedText text = ExtractedText.exact(pages, total > maxPages);
            if (!text.hasNoTextLayer()) {
                return text;
            }

            // No text layer: a scan. Read it with a model rather than rejecting
            // it - see the class javadoc.
            log.info("{}: no text layer over {} page(s), falling back to vision",
                    filename, readable);
            return visionRead(doc, filename, Math.min(readable, maxVisionPages),
                    total > maxVisionPages);

        } catch (IOException e) {
            throw new IntakeException(IntakeException.Cause.CORRUPT, filename, e.getMessage());
        }
    }

    /**
     * Renders each page and asks the vision reader to transcribe it.
     *
     * <p>If every page still comes back empty, the document is rejected. A scan
     * the model could not read must not become an empty document - that would
     * let an unreadable certificate pass as one containing no problems.
     */
    private ExtractedText visionRead(PDDocument doc, String filename, int pageLimit,
                                     boolean truncated) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        List<String> pages = new ArrayList<>(pageLimit);

        for (int p = 0; p < pageLimit; p++) {
            BufferedImage image = renderer.renderImageWithDPI(p, visionDpi);
            pages.add(visionReader.transcribe(toPng(image), filename, p + 1));
        }

        ExtractedText text = ExtractedText.fromVision(pages, truncated);
        if (text.hasNoTextLayer()) {
            throw new IntakeException(IntakeException.Cause.NO_TEXT_LAYER, filename,
                    "no text found on %d page(s), even by vision".formatted(pageLimit));
        }
        return text;
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
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
        return ExtractedText.exact(List.of(all.split("\f")), false);
    }
}
