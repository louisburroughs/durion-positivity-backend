package com.positivity.bulkloader.internal.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S100")
class SpreadsheetRecordFileParserTest {

    private final SpreadsheetRecordFileParser parser = new SpreadsheetRecordFileParser();

    @Test
    void supports_spreadsheetExtensions() {
        assertThat(parser.supports("catalog.xlsx")).isTrue();
        assertThat(parser.supports("catalog.XLSX")).isTrue();
        assertThat(parser.supports("catalog.xls")).isTrue();
        assertThat(parser.supports("catalog.csv")).isFalse();
    }

    @Test
    void open_multiSheetWorkbook_selectsSheetWithMostRecords() throws IOException {
        byte[] workbook = vendorStyleWorkbook();

        try (RecordSource source = parser.open(new ByteArrayInputStream(workbook), "vendor_catalog.xlsx")) {
            assertThat(source.headers())
                    .containsExactly("supplier_sku", "product_name", "category", "uom", "list_price");

            Map<String, String> first = source.nextRecord();
            assertThat(first)
                    .containsEntry("supplier_sku", "MIC-000001")
                    .containsEntry("product_name", "Maxion Steel Wheel 15x6")
                    .containsEntry("uom", "EA");
            // Numeric cells must render as displayed text, not scientific notation.
            assertThat(first.get("list_price")).isEqualTo("242.81");

            Map<String, String> second = source.nextRecord();
            assertThat(second).containsEntry("supplier_sku", "NAT-000004");
            Map<String, String> third = source.nextRecord();
            assertThat(third).containsEntry("supplier_sku", "COO-000007");
            assertThat(source.nextRecord()).isNull();
        }
    }

    /**
     * Mirrors the structure of the vendor example file: an instructional sheet first, the catalog
     * data sheet second, and a summary sheet last — the parser must pick the data sheet.
     */
    private byte[] vendorStyleWorkbook() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet importMap = workbook.createSheet("Import_Map");
            writeRow(importMap, 0, "Target POS Field", "Source Column");
            writeRow(importMap, 1, "SKU", "supplier_sku");

            Sheet catalog = workbook.createSheet("Catalog");
            writeRow(catalog, 0, "supplier_sku", "product_name", "category", "uom", "list_price");
            Row row1 = writeRow(catalog, 1, "MIC-000001", "Maxion Steel Wheel 15x6", "Wheels", "EA");
            row1.createCell(4).setCellValue(242.81);
            writeRow(catalog, 2, "NAT-000004", "Dill Valve Stem", "TPMS", "KIT", "96.26");
            writeRow(catalog, 3, "COO-000007", "Cooper Transforce HT2", "Tires", "EA", "565.94");

            Sheet summary = workbook.createSheet("Summary");
            writeRow(summary, 0, "Metric", "Value");
            writeRow(summary, 1, "Generated rows", "3");

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private Row writeRow(Sheet sheet, int rowIndex, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
        return row;
    }

    @Test
    void open_emptyWorkbook_throws() throws IOException {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Blank");
            workbook.write(out);
            bytes = out.toByteArray();
        }
        InputStream stream = new ByteArrayInputStream(bytes);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.open(stream, "empty.xlsx"))
                .isInstanceOf(RecordFileParseException.class)
                .hasMessageContaining("no sheet with a header row");
    }
}
