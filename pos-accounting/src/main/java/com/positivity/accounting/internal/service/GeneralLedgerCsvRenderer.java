package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.GeneralLedgerAccountSection;
import com.positivity.accounting.internal.dto.GeneralLedgerLine;
import com.positivity.accounting.internal.dto.GeneralLedgerReport;
import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link GeneralLedgerReport} as deterministic CSV (issue #1014).
 *
 * <p>Column and row order are fixed: metadata block (including the optional
 * account filter, {@code ALL} when unfiltered), then one flat table with
 * per-account sections in report order (ordered by account number). Each section
 * emits an OPENING BALANCE marker row, the chronological in-period lines with
 * running balance, and an ACCOUNT TOTAL marker row carrying the period
 * debit/credit totals and the closing balance; a GRAND TOTAL row closes the
 * table. All figures are emitted with {@link BigDecimal#toPlainString()} so the
 * CSV matches the JSON report to the cent. The volatile {@code generatedAt}
 * timestamp is intentionally excluded so identical report data always renders
 * byte-identical CSV.
 */
@Component
public class GeneralLedgerCsvRenderer {

    private static final String ROW_HEADER =
            "Account Number,Account Name,Entry Number,Transaction Date,Description,Debit,Credit,Running Balance";

    /**
     * Render the report to CSV.
     *
     * @param report the generated general ledger report
     * @return CSV text with Unix line endings and a trailing newline
     */
    @NonNull
    public String render(@NonNull GeneralLedgerReport report) {
        StringBuilder csv = new StringBuilder();

        csv.append("Report,Start Date,End Date,Account Filter\n");
        csv.append("GENERAL_LEDGER,")
                .append(report.getStartDate())
                .append(',')
                .append(report.getEndDate())
                .append(',')
                .append(report.getAccountId() == null ? "ALL" : CsvFormat.escape(report.getAccountId()))
                .append("\n\n");

        csv.append(ROW_HEADER).append('\n');
        for (GeneralLedgerAccountSection section : report.getAccounts()) {
            String number = CsvFormat.escape(section.getAccountNumber());
            String name = CsvFormat.escape(section.getAccountName());

            csv.append(number)
                    .append(',')
                    .append(name)
                    .append(",OPENING BALANCE,,,,,")
                    .append(amount(section.getOpeningBalance()))
                    .append('\n');
            for (GeneralLedgerLine line : section.getLines()) {
                csv.append(number)
                        .append(',')
                        .append(name)
                        .append(',')
                        .append(CsvFormat.escape(line.getEntryNumber()))
                        .append(',')
                        .append(line.getTransactionDate())
                        .append(',')
                        .append(CsvFormat.escape(line.getDescription()))
                        .append(',')
                        .append(amount(line.getDebitAmount()))
                        .append(',')
                        .append(amount(line.getCreditAmount()))
                        .append(',')
                        .append(amount(line.getRunningBalance()))
                        .append('\n');
            }
            csv.append(number)
                    .append(',')
                    .append(name)
                    .append(",ACCOUNT TOTAL,,,")
                    .append(amount(section.getTotalDebit()))
                    .append(',')
                    .append(amount(section.getTotalCredit()))
                    .append(',')
                    .append(amount(section.getClosingBalance()))
                    .append('\n');
        }
        csv.append("GRAND TOTAL,,,,,")
                .append(amount(report.getTotalDebit()))
                .append(',')
                .append(amount(report.getTotalCredit()))
                .append(",\n");

        return csv.toString();
    }

    private static String amount(BigDecimal value) {
        return CsvFormat.amount(value);
    }
}
