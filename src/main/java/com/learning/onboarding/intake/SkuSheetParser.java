package com.learning.onboarding.intake;

import com.learning.onboarding.domain.Sku;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the SKU list from a spreadsheet.
 *
 * <p><b>No model is involved, and that is the whole point.</b> A spreadsheet is
 * already structured - a GTIN sits in a cell, not buried in a sentence - so
 * asking a model to read it would introduce uncertainty where none exists and
 * cost a network call per hundred rows.
 *
 * <p>Everything this produces is {@link ExtractionSource#NATIVE_TEXT}: exact,
 * reproducible, and safe to base a DETERMINISTIC finding on.
 *
 * <h2>Header matching, not column positions</h2>
 * Column order varies between vendors, and reading "column C" would break the
 * first time someone inserted a column. Headers are matched by name, normalised
 * for case, spacing and punctuation, with the aliases vendors actually use.
 */
@Component
public class SkuSheetParser {

    private static final Logger log = LoggerFactory.getLogger(SkuSheetParser.class);

    /** Column aliases seen in real vendor templates. */
    private static final Map<String, List<String>> HEADERS = Map.of(
            "sku",         List.of("vendorsku", "sku", "vendorcode", "suppliersku",
                                   "itemcode", "partnumber", "partno", "modelno"),
            "description", List.of("description", "productdescription", "itemdescription",
                                   "productname", "itemname"),
            "gtin",        List.of("gtin", "ean", "barcode", "upc", "gtin13", "gtin14"),
            "casepack",    List.of("casepack", "caseqty", "unitspercase", "packsize",
                                   "innerqty", "qtypercase"),
            "weight",      List.of("caseweight", "caseweightkg", "weightkg", "grossweight",
                                   "weight"),
            "hazardous",   List.of("hazardous", "hazmat", "dangerousgoods", "adr",
                                   "hazardclass"));

    private final DataFormatter formatter = new DataFormatter();

    /**
     * @throws IntakeException if the file will not open or has no usable header row
     */
    public List<Sku> parse(byte[] content, String filename) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new IntakeException(IntakeException.Cause.CORRUPT, filename,
                        "spreadsheet has no rows");
            }

            // The header row index matters as much as the columns: data starts
            // after the HEADER, not after the first row. Getting that wrong
            // parses the header itself as a SKU called "SKU".
            Header header = findColumns(sheet, filename);
            Map<String, Integer> columns = header.columns();
            List<Sku> skus = new ArrayList<>();
            List<String> skipped = new ArrayList<>();

            for (int r = header.rowIndex() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isBlank(row)) {
                    continue;   // vendors leave spacer rows; not an error
                }
                try {
                    skus.add(toSku(row, columns));
                } catch (IllegalArgumentException e) {
                    // One bad row must not lose the other 399. Collected and
                    // reported, never silently dropped - a SKU that vanished
                    // between the vendor's file and our review is worse than
                    // one that was rejected loudly.
                    skipped.add("row " + (r + 1) + ": " + e.getMessage());
                }
            }

            if (!skipped.isEmpty()) {
                log.warn("{}: {} row(s) could not be read: {}", filename, skipped.size(), skipped);
            }
            if (skus.isEmpty()) {
                throw new IntakeException(IntakeException.Cause.CORRUPT, filename,
                        "no readable SKU rows; problems were: " + skipped);
            }
            return skus;

        } catch (IOException e) {
            throw new IntakeException(IntakeException.Cause.CORRUPT, filename, e.getMessage());
        }
    }

    /** Where the header row is, and which column holds what. */
    private record Header(int rowIndex, Map<String, Integer> columns) {}

    /**
     * Locates the header row and each column within it.
     *
     * <p>Scans the first few rows rather than assuming row 1: vendor templates
     * routinely open with a title row, a logo, or a blank line before the real
     * header.
     */
    private Header findColumns(Sheet sheet, String filename) {
        int firstRow = sheet.getFirstRowNum();
        for (int r = firstRow; r <= Math.min(firstRow + 5, sheet.getLastRowNum()); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, Integer> found = new HashMap<>();
            for (Cell cell : row) {
                String header = normalise(formatter.formatCellValue(cell));
                HEADERS.forEach((field, aliases) -> {
                    if (aliases.contains(header)) {
                        found.putIfAbsent(field, cell.getColumnIndex());
                    }
                });
            }
            // A header row is only a header row if it has the columns we cannot
            // do without. Anything less is a title or a note.
            if (found.containsKey("sku") && found.containsKey("description")) {
                return new Header(r, found);
            }
        }
        throw new IntakeException(IntakeException.Cause.CORRUPT, filename,
                "no header row found with recognisable SKU and description columns");
    }

    private Sku toSku(Row row, Map<String, Integer> columns) {
        String vendorSku = text(row, columns.get("sku"));
        String description = text(row, columns.get("description"));
        String gtin = text(row, columns.get("gtin"));

        // Absent optional columns default rather than failing: many vendors omit
        // case weight entirely, and rejecting the row would lose the SKU over a
        // field the logistics reviewer can chase separately.
        int casePack = number(row, columns.get("casepack")).map(BigDecimal::intValue).orElse(1);
        BigDecimal weight = number(row, columns.get("weight")).orElse(BigDecimal.ONE);
        boolean hazardous = flag(row, columns.get("hazardous"));

        return new Sku(vendorSku, description,
                gtin == null || gtin.isBlank() ? null : gtin.replaceAll("\\s", ""),
                casePack, weight, hazardous);
    }

    private String text(Row row, Integer column) {
        if (column == null) {
            return null;
        }
        Cell cell = row.getCell(column);
        return cell == null ? null : formatter.formatCellValue(cell).trim();
    }

    /**
     * Reads a number from a cell that may be numeric or text.
     *
     * <p>Both happen. A case pack typed as "12" in a text-formatted column is
     * still 12, and refusing it would reject a valid file over spreadsheet
     * formatting the vendor probably did not choose.
     */
    private java.util.Optional<BigDecimal> number(Row row, Integer column) {
        String raw = text(row, column);
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new BigDecimal(raw.replaceAll("[^\\d.]", "")));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private boolean flag(Row row, Integer column) {
        String raw = text(row, column);
        if (raw == null) {
            return false;
        }
        String v = raw.trim().toLowerCase();
        return v.equals("y") || v.equals("yes") || v.equals("true") || v.equals("1")
                || v.equals("hazardous") || v.equals("hazmat");
    }

    private boolean isBlank(Row row) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** Case, spacing and punctuation are not meaningful in a column header. */
    private static String normalise(String header) {
        return header == null ? "" : header.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
