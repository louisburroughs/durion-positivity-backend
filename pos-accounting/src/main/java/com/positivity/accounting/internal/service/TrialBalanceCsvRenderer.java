package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.EntryNumberGapCheck;
import com.positivity.accounting.internal.dto.TrialBalanceReport;
import com.positivity.accounting.internal.dto.TrialBalanceRow;
import java.math.BigDecimal;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link TrialBalanceReport} as deterministic CSV (issue #1013).
 *
 * <p>Column and row order are fixed: metadata block (the export request's
 * {@code endDate} is used as the as-of date), per-account rows in report order
 * (ordered by account number), a TOTAL row proving the debit/credit balance
 * constraint, the balanced flag, and the entry-number gap-check footnote block
 * (header always present; no rows on a clean ledger). All figures are emitted
 * with {@link BigDecimal#toPlainString()} so the CSV matches the JSON report to
 * the cent. The volatile {@code generatedAt} timestamp is intentionally excluded
 * so identical report data always renders byte-identical CSV.
 */
@Component
public class TrialBalanceCsvRenderer {

    private static final String ROW_HEADER = "Account Number,Account Name,Total Debit,Total Credit,Balance";
    private static final String GAP_HEADER = "Sequence Scope,Missing Entry Numbers";

    /**
     * Render the report to CSV.
     *
     * @param report the generated trial balance report
     * @return CSV text with Unix line endings and a trailing newline
     */
    @NonNull
    public String render(@NonNull TrialBalanceReport report) {
        StringBuilder csv = new StringBuilder();

        csv.append("Report,As-Of Date\n");
        csv.append("TRIAL_BALANCE,").append(report.getAsOfDate()).append("\n\n");

        csv.append(ROW_HEADER).append('\n');
        for (TrialBalanceRow row : report.getRows()) {
            csv.append(CsvFormat.escape(row.getAccountNumber()))
                    .append(',')
                    .append(CsvFormat.escape(row.getAccountName()))
                    .append(',')
                    .append(amount(row.getTotalDebit()))
                    .append(',')
                    .append(amount(row.getTotalCredit()))
                    .append(',')
                    .append(amount(row.getBalance()))
                    .append('\n');
        }
        csv.append("TOTAL,,")
                .append(amount(report.getTotalDebit()))
                .append(',')
                .append(amount(report.getTotalCredit()))
                .append(",\n");
        csv.append("BALANCED,").append(report.getBalanced()).append("\n\n");

        csv.append(GAP_HEADER).append('\n');
        for (EntryNumberGapCheck gap : report.getEntryNumberGaps()) {
            csv.append(CsvFormat.escape(gap.getScopeKey()))
                    .append(',')
                    .append(CsvFormat.escape(gap.getMissingNumbers().stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining("|"))))
                    .append('\n');
        }

        return csv.toString();
    }

    private static String amount(BigDecimal value) {
        return CsvFormat.amount(value);
    }
}
