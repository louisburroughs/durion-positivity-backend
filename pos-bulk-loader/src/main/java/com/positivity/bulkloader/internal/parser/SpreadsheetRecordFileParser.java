package com.positivity.bulkloader.internal.parser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Header-driven parser for Excel workbooks. Each sheet's header row is the first non-empty row;
 * the sheet with the most data rows is treated as the record sheet, so companion sheets
 * (instructions, data dictionaries, summaries) are ignored.
 */
@Component
@Slf4j
public class SpreadsheetRecordFileParser implements RecordFileParser {

    private static final Set<String> EXTENSIONS = Set.of("xlsx", "xlsm", "xls");

    @Override
    public boolean supports(@NonNull String fileName) {
        return EXTENSIONS.contains(FileNames.extension(fileName));
    }

    @Override
    @NonNull
    public RecordSource open(@NonNull InputStream content, @NonNull String fileName) {
        try (content;
                Workbook workbook = WorkbookFactory.create(content)) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            ParsedSheet best = null;
            for (Sheet sheet : workbook) {
                ParsedSheet parsed = parseSheet(sheet, formatter, evaluator);
                if (parsed != null
                        && (best == null
                                || parsed.records().size() > best.records().size())) {
                    best = parsed;
                }
            }
            if (best == null) {
                throw new RecordFileParseException("Workbook contains no sheet with a header row: " + fileName);
            }
            log.debug(
                    "Selected sheet '{}' with {} records from workbook {}",
                    best.sheetName(),
                    best.records().size(),
                    fileName);
            return new ListBackedRecordSource(best.headers(), best.records());
        } catch (IOException e) {
            throw new RecordFileParseException("Failed to read spreadsheet file: " + fileName, e);
        }
    }

    @Nullable
    private ParsedSheet parseSheet(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        Row headerRow = findHeaderRow(sheet, formatter, evaluator);
        if (headerRow == null) {
            return null;
        }

        List<String> headers = new ArrayList<>();
        int lastHeaderCell = headerRow.getLastCellNum();
        for (int column = 0; column < lastHeaderCell; column++) {
            String value = cellText(headerRow.getCell(column), formatter, evaluator);
            headers.add(value.isEmpty() ? "column_" + (column + 1) : value);
        }

        List<Map<String, String>> records = new ArrayList<>();
        for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<String, String> record = LinkedHashMap.newLinkedHashMap(headers.size());
            boolean hasValue = false;
            for (int column = 0; column < headers.size(); column++) {
                String value = cellText(row.getCell(column), formatter, evaluator);
                hasValue = hasValue || !value.isEmpty();
                record.put(headers.get(column), value);
            }
            if (hasValue) {
                records.add(record);
            }
        }
        return new ParsedSheet(sheet.getSheetName(), headers, records);
    }

    @Nullable
    private Row findHeaderRow(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (!cellText(cell, formatter, evaluator).isEmpty()) {
                    return row;
                }
            }
        }
        return null;
    }

    private String cellText(@Nullable Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell, evaluator).trim();
    }

    private record ParsedSheet(String sheetName, List<String> headers, List<Map<String, String>> records) {}
}
