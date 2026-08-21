package com.learning.onboarding.intake;

import com.learning.onboarding.domain.Sku;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading the SKU list from the spreadsheet a vendor actually sends.
 *
 * <p>Workbooks are built in memory so each test states exactly what is in the
 * file it asserts on. The awkward cases here are all real: title rows above the
 * header, columns in a different order, case packs typed as text, a blank
 * spacer row in the middle.
 */
class SkuSheetParserTest {

    private final SkuSheetParser parser = new SkuSheetParser();

    /** Builds an .xlsx from rows of cell values. */
    private static byte[] sheet(List<List<String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("SKUs");
            for (int r = 0; r < rows.size(); r++) {
                Row row = s.createRow(r);
                List<String> cells = rows.get(r);
                for (int c = 0; c < cells.size(); c++) {
                    if (cells.get(c) != null) {
                        row.createCell(c).setCellValue(cells.get(c));
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("reads a straightforward SKU list")
    void readsBasicSheet() throws Exception {
        byte[] xlsx = sheet(List.of(
                List.of("Vendor SKU", "Description", "GTIN", "Case Pack", "Case Weight KG", "Hazardous"),
                List.of("ACM-DRL-18V", "18V cordless drill, 2Ah, 2-pack", "5012345678900", "6", "12.4", "N"),
                List.of("ACM-SOLV-5L", "Solvent cleaner 5L", "5012345678917", "4", "21.0", "Y")));

        List<Sku> skus = parser.parse(xlsx, "skus.xlsx");

        assertEquals(2, skus.size());

        Sku drill = skus.get(0);
        assertEquals("ACM-DRL-18V", drill.vendorSku());
        assertEquals("5012345678900", drill.gtin());
        assertEquals(6, drill.casePack());
        assertEquals(0, new BigDecimal("12.4").compareTo(drill.caseWeightKg()));
        assertFalse(drill.hazardous());

        assertTrue(skus.get(1).hazardous(),
                "one hazardous SKU changes carrier and store handling for the whole delivery");
    }

    @Test
    @DisplayName("finds the header row under a title row")
    void skipsTitleRows() throws Exception {
        // Vendor templates routinely open with a title and a blank line.
        byte[] xlsx = sheet(List.of(
                List.of("ACME TOOLS LTD - NEW LINE SUBMISSION"),
                List.of(""),
                List.of("SKU", "Product Description", "EAN", "Units Per Case"),
                List.of("ACM-HAM-16", "Claw hammer 16oz", "5012345678924", "12")));

        List<Sku> skus = parser.parse(xlsx, "skus.xlsx");

        assertEquals(1, skus.size());
        assertEquals("ACM-HAM-16", skus.get(0).vendorSku());
    }

    @Test
    @DisplayName("matches columns by header name, not position")
    void columnOrderDoesNotMatter() throws Exception {
        // Same data, columns reordered and named differently. Reading "column C"
        // would break the first time a vendor inserted a column.
        byte[] xlsx = sheet(List.of(
                List.of("Barcode", "Item Code", "Qty Per Case", "Product Name"),
                List.of("5012345678931", "ACM-SAW-22", "8", "Hand saw 22 inch")));

        List<Sku> skus = parser.parse(xlsx, "skus.xlsx");

        Sku saw = skus.get(0);
        assertEquals("ACM-SAW-22", saw.vendorSku());
        assertEquals("Hand saw 22 inch", saw.description());
        assertEquals("5012345678931", saw.gtin());
        assertEquals(8, saw.casePack());
    }

    @Test
    @DisplayName("a blank spacer row is skipped, not treated as an error")
    void skipsBlankRows() throws Exception {
        byte[] xlsx = sheet(List.of(
                List.of("SKU", "Description", "Case Pack"),
                List.of("ACM-1", "Item one", "6"),
                List.of("", "", ""),
                List.of("ACM-2", "Item two", "6")));

        assertEquals(2, parser.parse(xlsx, "skus.xlsx").size());
    }

    @Test
    @DisplayName("one unreadable row does not lose the rest")
    void badRowDoesNotLoseTheFile() throws Exception {
        byte[] xlsx = sheet(List.of(
                List.of("SKU", "Description", "Case Pack"),
                List.of("ACM-1", "Item one", "6"),
                List.of("ACM-2", "", "6"),          // no description - rejected
                List.of("ACM-3", "Item three", "6")));

        List<Sku> skus = parser.parse(xlsx, "skus.xlsx");

        assertEquals(2, skus.size(), "the two good rows must survive");
        assertTrue(skus.stream().noneMatch(s -> s.vendorSku().equals("ACM-2")));
    }

    @Test
    @DisplayName("a case pack typed as text still parses")
    void handlesTextFormattedNumbers() throws Exception {
        // Spreadsheet formatting is not the vendor's deliberate choice, and
        // rejecting a valid file over it would be pedantry.
        byte[] xlsx = sheet(List.of(
                List.of("SKU", "Description", "Case Pack", "Weight KG"),
                List.of("ACM-1", "Item one", " 12 ", "3.5 kg")));

        Sku sku = parser.parse(xlsx, "skus.xlsx").get(0);

        assertEquals(12, sku.casePack());
        assertEquals(0, new BigDecimal("3.5").compareTo(sku.caseWeightKg()));
    }

    @Test
    @DisplayName("missing optional columns default rather than failing")
    void optionalColumnsDefault() throws Exception {
        byte[] xlsx = sheet(List.of(
                List.of("SKU", "Description"),
                List.of("ACM-1", "Item with no case data")));

        Sku sku = parser.parse(xlsx, "skus.xlsx").get(0);

        assertEquals(1, sku.casePack());
        assertNull(sku.gtin(), "an absent GTIN is a finding for logistics, not a parse failure");
    }

    @Test
    @DisplayName("a sheet with no recognisable header is rejected")
    void rejectsUnrecognisableSheet() throws Exception {
        byte[] xlsx = sheet(List.of(
                List.of("some", "unrelated", "spreadsheet"),
                List.of("1", "2", "3")));

        var e = assertThrows(IntakeException.class, () -> parser.parse(xlsx, "wrong.xlsx"));
        assertEquals(IntakeException.Cause.CORRUPT, e.reason());
    }

    @Test
    @DisplayName("a GTIN with stray spaces is cleaned")
    void cleansGtin() throws Exception {
        byte[] xlsx = sheet(List.of(
                List.of("SKU", "Description", "GTIN"),
                List.of("ACM-1", "Item one", "5012 3456 7890 0")));

        assertEquals("5012345678900", parser.parse(xlsx, "skus.xlsx").get(0).gtin());
    }
}
