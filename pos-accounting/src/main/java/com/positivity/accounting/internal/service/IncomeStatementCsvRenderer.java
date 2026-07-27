package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.IncomeStatementReport;
import java.math.BigDecimal;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Renders an {@link IncomeStatementReport} as deterministic CSV (issue #1011).
 *
 * <p>Column and row order are fixed: metadata block, then one row per statement
 * line in report order (the service builds {@code lineItems} as a
 * {@code LinkedHashMap} in statement-line display order), then the total rows.
 * All figures are emitted with {@link BigDecimal#toPlainString()} so the CSV
 * matches the JSON report to the cent. The volatile {@code generatedAt}
 * timestamp is intentionally excluded so identical report data always renders
 * byte-identical CSV.
 */
@Component
public class IncomeStatementCsvRenderer {

    /**
     * Render the report to CSV.
     *
     * @param report the generated income statement report
     * @return CSV text with Unix line endings and a trailing newline
     */
    @NonNull
    public String render(@NonNull IncomeStatementReport report) {
        StringBuilder csv = new StringBuilder();

        csv.append("Report,Start Date,End Date\n");
        csv.append("INCOME_STATEMENT,")
                .append(report.getStartDate())
                .append(',')
                .append(report.getEndDate())
                .append("\n\n");

        csv.append("Line Item,Amount\n");
        for (Map.Entry<String, BigDecimal> line : report.getLineItems().entrySet()) {
            csv.append(CsvFormat.escapeText(line.getKey()))
                    .append(',')
                    .append(CsvFormat.amount(line.getValue()))
                    .append('\n');
        }
        csv.append("TOTAL_REVENUE,")
                .append(CsvFormat.amount(report.getTotalRevenue()))
                .append('\n');
        csv.append("TOTAL_EXPENSES,")
                .append(CsvFormat.amount(report.getTotalExpenses()))
                .append('\n');
        csv.append("NET_INCOME,")
                .append(CsvFormat.amount(report.getNetIncome()))
                .append('\n');

        return csv.toString();
    }
}
