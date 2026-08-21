package com.learning.onboarding.intake;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extraction, and the boundary conditions a vendor-supplied file can hit.
 *
 * <p>The PDFs here are built in memory rather than checked in as fixtures, so
 * the test states exactly what is in the file it is asserting on - a binary
 * fixture would hide that.
 */
class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor(20_971_520L, 200);

    /** Builds a real PDF with the given text, one page per entry. */
    private static byte[] pdfWith(String... pageTexts) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    for (String line : text.split("\n")) {
                        cs.showText(line);
                        cs.newLineAtOffset(0, -16);
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("reads a real PDF, keeping pages separate")
    void extractsPdfPages() throws Exception {
        byte[] pdf = pdfWith(
                "Certificate of Conformity",
                "Valid until: 12 April 2026");

        ExtractedText text = extractor.extract(pdf, "cert.pdf");

        assertEquals(2, text.pageCount());
        assertTrue(text.page(1).contains("Certificate of Conformity"));
        assertTrue(text.page(2).contains("12 April 2026"));
        assertFalse(text.truncated());
        assertEquals(ExtractionSource.NATIVE_TEXT, text.source());
        assertTrue(text.supportsDeterministicFindings());
    }

    @Test
    @DisplayName("pages stay separate so citations can name one")
    void pagesSupportCitations() throws Exception {
        byte[] pdf = pdfWith("page one text", "Valid until: 12 April 2026");
        ExtractedText text = extractor.extract(pdf, "cert.pdf");

        // The expiry is on page 2, and a reviewer must be told that rather than
        // being sent to search a 40-page document.
        FactExtractor facts = new FactExtractor();
        var page2 = facts.extract(text.page(2), 2);

        assertEquals(2, page2.expiry().orElseThrow().asEvidence("cert.pdf").page());
    }

    /** A valid PDF page with no text content - exactly what a scan produces. */
    private static byte[] scannedPdf() throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("a scan is read by vision and tagged as such")
    void scannedPdfTakesVisionPath() throws Exception {
        ScannedPageReader stub = (image, doc, page) -> "Valid until: 12 April 2026";
        TextExtractor withVision = new TextExtractor(20_971_520L, 200, 15, 72, stub);

        ExtractedText text = withVision.extract(scannedPdf(), "scanned-cert.pdf");

        assertEquals(ExtractionSource.MODEL_VISION, text.source());
        assertTrue(text.fullText().contains("12 April 2026"));
        assertFalse(text.supportsDeterministicFindings(),
                "a date a model read off a smudged scan is not arithmetic, however "
                        + "exact the comparison afterwards is");
    }

    @Test
    @DisplayName("a scan is rejected when vision is switched off")
    void scannedPdfRejectedWithoutVision() throws Exception {
        var e = assertThrows(IntakeException.class,
                () -> extractor.extract(scannedPdf(), "scanned-cert.pdf"));

        assertEquals(IntakeException.Cause.NO_TEXT_LAYER, e.reason(),
                "a scanned certificate that yields no text must escalate - "
                        + "otherwise the vendor looks compliant because their "
                        + "certificate was unreadable");
    }

    @Test
    @DisplayName("a scan vision cannot read is rejected, not passed on as empty")
    void unreadableScanIsRejected() throws Exception {
        ScannedPageReader blind = (image, doc, page) -> "";
        TextExtractor withVision = new TextExtractor(20_971_520L, 200, 15, 72, blind);

        var e = assertThrows(IntakeException.class,
                () -> withVision.extract(scannedPdf(), "unreadable.pdf"));

        assertEquals(IntakeException.Cause.NO_TEXT_LAYER, e.reason());
    }

    @Test
    @DisplayName("content decides the parser, not the file extension")
    void sniffsContentNotExtension() {
        byte[] plain = "Valid until: 12 April 2026".getBytes(StandardCharsets.UTF_8);

        // Named .pdf but is not one. Handled as text rather than failing.
        ExtractedText text = extractor.extract(plain, "certificate.pdf");
        assertTrue(text.fullText().contains("12 April 2026"));
    }

    @Test
    @DisplayName("oversized files are refused at the boundary")
    void oversizedIsRefused() {
        TextExtractor tiny = new TextExtractor(100L, 200);
        byte[] big = new byte[200];
        java.util.Arrays.fill(big, (byte) 'a');

        var e = assertThrows(IntakeException.class, () -> tiny.extract(big, "huge.txt"));
        assertEquals(IntakeException.Cause.TOO_LARGE, e.reason());
    }

    @Test
    @DisplayName("page limit truncates and says so")
    void pageLimitIsReported() throws Exception {
        TextExtractor limited = new TextExtractor(20_971_520L, 2);
        byte[] pdf = pdfWith("one", "two", "three", "four");

        ExtractedText text = limited.extract(pdf, "long.pdf");

        assertEquals(2, text.pageCount());
        assertTrue(text.truncated(),
                "silently dropping pages would hide evidence a reviewer needs");
    }

    @Test
    @DisplayName("a corrupt PDF fails with a typed cause")
    void corruptPdfIsTyped() {
        byte[] notReallyAPdf = "%PDF-1.4 then complete nonsense".getBytes(StandardCharsets.UTF_8);

        var e = assertThrows(IntakeException.class,
                () -> extractor.extract(notReallyAPdf, "broken.pdf"));
        assertEquals(IntakeException.Cause.CORRUPT, e.reason());
    }

    @Test
    @DisplayName("an empty upload is refused")
    void emptyIsRefused() {
        var e = assertThrows(IntakeException.class,
                () -> extractor.extract(new byte[0], "nothing.pdf"));
        assertEquals(IntakeException.Cause.CORRUPT, e.reason());
    }

    @Test
    @DisplayName("plain text becomes one page")
    void plainTextIsOnePage() {
        ExtractedText text = extractor.extract(
                "EDI capability: ASN supported".getBytes(StandardCharsets.UTF_8), "edi.txt");

        assertEquals(1, text.pageCount());
        assertEquals(List.of("EDI capability: ASN supported"), text.pages());
    }
}
