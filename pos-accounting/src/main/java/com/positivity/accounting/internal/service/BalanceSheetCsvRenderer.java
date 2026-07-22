package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.BalanceSheetReport;
import java.math.BigDecimal;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link BalanceSheetReport} as deterministic CSV (issue #1012).
 *
 * <p>Column and row order are fixed: metadata block (the export request's
 * {@code endDate} is used as the as-of date), then one row per statement line in
 * report order (the service builds {@code lineItems} as a {@code LinkedHashMap}
 * in statement-line display order), then total rows and the balanced flag. All
 * figures are emitted with {@link BigDecimal#toPlainString()} so the CSV matches
 * the JSON report to the cent. The volatile {@code generatedAt} timestamp is
 * intentionally excluded so identical report data always renders byte-identical
 * CSV.
 */
@Component
public class BalanceSheetCsvRenderer {

    /**
     * Render the report to CSV.
     *
     * @param report the generated balance sheet report
     * @return CSV text with Unix line endings and a trailing newline
     */
    @NonNull
    public String render(@NonNull BalanceSheetReport report) {
        StringBuilder csv = new StringBuilder();

        csv.append("Report,As-Of Date\n");
        csv.append("BALANCE_SHEET,").append(report.getAsOfDate()).append("\n\n");

        csv.append("Line Item,Amount\n");
        for (Map.Entry<String, BigDecimal> line : report.getLineItems().entrySet()) {
            csv.append(CsvFormat.escapeText(line.getKey()))
                    .append(',')
                    .append(CsvFormat.amount(line.getValue()))
                    .append('\n');
        }
        csv.append("TOTAL_ASSETS,")
                .append(CsvFormat.amount(report.getTotalAssets()))
                .append('\n');
        csv.append("TOTAL_LIABILITIES,")
                .append(CsvFormat.amount(report.getTotalLiabilities()))
                .append('\n');
        csv.append("TOTAL_EQUITY,")
                .append(CsvFormat.amount(report.getTotalEquity()))
                .append('\n');
        csv.append("BALANCED,").append(report.getBalanced()).append('\n');

        return csv.toString();
    }
}
