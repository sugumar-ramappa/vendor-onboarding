package com.learning.onboarding.web;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.domain.DeliveryModel;
import com.learning.onboarding.domain.DocumentType;
import com.learning.onboarding.domain.ProductCategory;
import com.learning.onboarding.intake.ExtractionSource;
import com.learning.onboarding.intake.FactExtractor;
import com.learning.onboarding.intake.IntakeException;
import com.learning.onboarding.intake.SkuSheetParser;
import com.learning.onboarding.intake.TextExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real bytes, through the real extractors, to a ReviewContext.
 *
 * <p>The gap this closes: the review pipeline had only ever been fed
 * pre-extracted text. The extractors were tested, and the reviewers were tested,
 * and nothing tested that a file arriving from outside becomes something a
 * reviewer can read.
 */
class IntakeServiceTest {

    private final IntakeService intake = new IntakeService(
            new TextExtractor(20_971_520L, 200), new SkuSheetParser(), new FactExtractor());

    // ------------------------------------------------------------- helpers --

    private static byte[] pdf(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.setLeading(14);
                content.newLineAtOffset(50, 700);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] skuSheet() throws Exception {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("SKUs");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Vendor SKU");
            header.createCell(1).setCellValue("Description");
            header.createCell(2).setCellValue("GTIN");
            header.createCell(3).setCellValue("Case Pack");
            header.createCell(4).setCellValue("Case Weight Kg");

            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("ACM-DRL-18V");
            row.createCell(1).setCellValue("18V cordless drill");
            row.createCell(2).setCellValue("5012345678900");
            row.createCell(3).setCellValue(6);
            row.createCell(4).setCellValue(12.4);

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static ApplicationSubmission submission(
            List<ApplicationSubmission.DeclaredDocument> declared) {
        return new ApplicationSubmission("APP-2026-0113", "Acme Tools Ltd",
                ProductCategory.POWER_TOOLS, DeliveryModel.DISTRIBUTION_CENTRE,
                Instant.now().plus(90, ChronoUnit.DAYS), declared, null);
    }

    private static ApplicationSubmission.DeclaredDocument declare(String name, DocumentType type) {
        return new ApplicationSubmission.DeclaredDocument(name, type);
    }

    // --------------------------------------------------------------- tests --

    @Test
    @DisplayName("a real PDF and a real spreadsheet become a reviewable context")
    void endToEndFromBytes() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("elec-cert.pdf", pdf(
                "ELECTRICAL SAFETY CERTIFICATE",
                "Scope of certification:",
                "Hand tools and non-powered garden implements",
                "Tested to: EN 62841",
                "Valid until: 12 April 2028"));

        ReviewContext context = intake.assemble(
                submission(List.of(declare("elec-cert.pdf",
                        DocumentType.ELECTRICAL_SAFETY_CERTIFICATE))),
                files, skuSheet());

        assertEquals("APP-2026-0113", context.applicationId());
        assertEquals(1, context.documents().size());
        assertEquals(1, context.application().skus().size());
        assertEquals("ACM-DRL-18V", context.application().skus().getFirst().vendorSku());

        // The text really came out of the PDF, not from a string somewhere.
        assertTrue(context.documents().getFirst().text().contains("EN 62841"));
        assertTrue(context.documents().getFirst().text().contains("non-powered"));

        // Provenance is recorded, which is what later decides whether a finding
        // may be DETERMINISTIC and whether its quote can be grounded.
        assertEquals(ExtractionSource.NATIVE_TEXT, context.sources().get("elec-cert.pdf"));

        // And the deterministic pass ran, so the model is never asked to do
        // date arithmetic on this certificate.
        assertNotNull(context.facts().get("elec-cert.pdf"));
    }

    @Test
    @DisplayName("a declared document with no file is rejected, naming it")
    void declaredButNotUploaded() throws Exception {
        var e = assertThrows(IntakeException.class, () -> intake.assemble(
                submission(List.of(declare("insurance.pdf", DocumentType.INSURANCE_CERTIFICATE))),
                Map.of("elec-cert.pdf", pdf("something else")), skuSheet()));

        assertTrue(e.getMessage().contains("insurance.pdf"),
                "the error must name the missing file: " + e.getMessage());
    }

    @Test
    @DisplayName("an uploaded file nobody declared is rejected rather than guessed at")
    void uploadedButNotDeclared() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("elec-cert.pdf", pdf("ELECTRICAL SAFETY CERTIFICATE"));
        files.put("mystery.pdf", pdf("who knows"));

        var e = assertThrows(IntakeException.class, () -> intake.assemble(
                submission(List.of(declare("elec-cert.pdf",
                        DocumentType.ELECTRICAL_SAFETY_CERTIFICATE))),
                files, skuSheet()));

        // Accepting it silently would file it as nothing, and the completeness
        // gate would then ask the vendor for a document sitting in the request.
        assertTrue(e.getMessage().contains("mystery.pdf"), e.getMessage());
    }

    @Test
    @DisplayName("an encrypted PDF is the vendor's problem, and says so")
    void encryptedPdfIsRejected() throws Exception {
        byte[] encrypted;
        try (PDDocument doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner", "", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            doc.save(out);
            encrypted = out.toByteArray();
        }

        var e = assertThrows(IntakeException.class, () -> intake.assemble(
                submission(List.of(declare("locked.pdf", DocumentType.INSURANCE_CERTIFICATE))),
                Map.of("locked.pdf", encrypted), skuSheet()));

        assertEquals(IntakeException.Cause.ENCRYPTED, e.reason());
    }

    @Test
    @DisplayName("no SKUs at all is refused - there is nothing to review")
    void noSkusIsRefused() throws Exception {
        var e = assertThrows(IntakeException.class, () -> intake.assemble(
                submission(List.of(declare("elec-cert.pdf",
                        DocumentType.ELECTRICAL_SAFETY_CERTIFICATE))),
                Map.of("elec-cert.pdf", pdf("cert")), null));

        assertTrue(e.getMessage().contains("SKU"), e.getMessage());
    }

    @Test
    @DisplayName("the same filename declared twice is refused")
    void duplicateFilenamesAreRefused() throws Exception {
        var e = assertThrows(IntakeException.class, () -> intake.assemble(
                submission(List.of(
                        declare("cert.pdf", DocumentType.ELECTRICAL_SAFETY_CERTIFICATE),
                        declare("cert.pdf", DocumentType.INSURANCE_CERTIFICATE))),
                Map.of("cert.pdf", pdf("cert")), skuSheet()));

        // Findings cite documents by name. Two with the same name would be
        // indistinguishable in the audit trail.
        assertTrue(e.getMessage().contains("twice"), e.getMessage());
    }
}
