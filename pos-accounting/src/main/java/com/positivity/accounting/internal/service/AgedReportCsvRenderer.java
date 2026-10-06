package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.AgedPayablesReport;
import com.positivity.accounting.internal.dto.AgedPayablesRow;
import com.positivity.accounting.internal.dto.AgedReceivablesReport;
import com.positivity.accounting.internal.dto.AgedReceivablesRow;
import com.positivity.accounting.internal.dto.AgingSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Renders {@link AgedReceivablesReport} and {@link AgedPayablesReport} as
 * deterministic CSV (issue #1015). The two reports are mirrors, so one renderer
 * covers both; only the report key and the party column headers differ.
 *
 * <p>Column and row order are fixed: metadata block (the export request's
 * {@code endDate} is used as the as-of date), per-party rows in the
 * deterministic order produced by the report DTO, with the aging-bucket columns
 * defined by the report DTOs (not yet due / 1-30 / 31-60 / 61-90 / 90+ / overdue),
 * each row's total, and a grand-total row; receivables carry the customer name and
 * number, payables the unaged bills not yet approved (CAP:550 S35, #2524).
 * All figures are emitted with {@link BigDecimal#toPlainString()} so the CSV
 * matches the JSON report to the cent. The volatile {@code generatedAt}
 * timestamp is intentionally excluded so identical report data always renders
 * byte-identical CSV.
 */
@Component
public class AgedReportCsvRenderer {

    private static final String BUCKET_COLUMNS =
            "Not Yet Due,1-30 Days,31-60 Days,61-90 Days,90+ Days,Overdue,Total Outstanding";

    /** Payables add the unaged bills not yet approved beside the buckets (AW11; CAP:550 S35, #2524). */
    private static final String UNAPPROVED_COLUMNS = "Unapproved,Unapproved Bills,Total Incl. Unapproved";

    /**
     * Render the aged receivables report to CSV.
     *
     * @param report the generated aged AR report
     * @return CSV text with Unix line endings and a trailing newline
     */
    @NonNull
    public String render(@NonNull AgedReceivablesReport report) {
        StringBuilder csv = header("AGED_RECEIVABLES", report.getAsOfDate());
        csv.append("Customer ID,Customer Name,Customer Number,")
                .append(BUCKET_COLUMNS)
                .append('\n');
        for (AgedReceivablesRow row : report.getRows()) {
            csv.append(CsvFormat.escapeText(row.getCustomerId().toString()))
                    .append(',')
                    .append(CsvFormat.escapeText(row.getCustomerName()))
                    .append(',')
                    .append(CsvFormat.escapeText(row.getCustomerReference()))
                    .append(',');
            appendBuckets(
                    csv,
                    row.getNotYetDue(),
                    row.getDays1To30(),
                    row.getDays31To60(),
                    row.getDays61To90(),
                    row.getDays90Plus(),
                    row.getOverdue(),
                    row.getTotalOutstanding());
            csv.append('\n');
        }
        csv.append("TOTAL,,,");
        appendTotals(csv, report.getTotals());
        csv.append('\n');
        return csv.toString();
    }

    /**
     * Render the aged payables report to CSV.
     *
     * @param report the generated aged AP report
     * @return CSV text with Unix line endings and a trailing newline
     */
    @NonNull
    public String render(@NonNull AgedPayablesReport report) {
        StringBuilder csv = header("AGED_PAYABLES", report.getAsOfDate());
        csv.append("Vendor ID,Vendor Name,")
                .append(BUCKET_COLUMNS)
                .append(',')
                .append(UNAPPROVED_COLUMNS)
                .append('\n');
        for (AgedPayablesRow row : report.getRows()) {
            csv.append(CsvFormat.escapeText(row.getVendorId().toString()))
                    .append(',')
                    .append(CsvFormat.escapeText(row.getVendorName()))
                    .append(',');
            appendBuckets(
                    csv,
                    row.getNotYetDue(),
                    row.getDays1To30(),
                    row.getDays31To60(),
                    row.getDays61To90(),
                    row.getDays90Plus(),
                    row.getOverdue(),
                    row.getTotalOutstanding());
            csv.append(',')
                    .append(amount(row.getUnapproved()))
                    .append(',')
                    .append(row.getUnapprovedBillCount())
                    .append(',')
                    .append(amount(row.getTotalIncludingUnapproved()))
                    .append('\n');
        }
        csv.append("TOTAL,,");
        appendTotals(csv, report.getTotals());
        csv.append(',')
                .append(amount(report.getUnapproved()))
                .append(',')
                .append(report.getUnapprovedBillCount())
                .append(',')
                .append(amount(report.getTotalIncludingUnapproved()))
                .append('\n');
        return csv.toString();
    }

    private static StringBuilder header(String reportKey, LocalDate asOfDate) {
        StringBuilder csv = new StringBuilder();
        csv.append("Report,As-Of Date\n");
        csv.append(reportKey).append(',').append(asOfDate).append("\n\n");
        return csv;
    }

    private static void appendTotals(StringBuilder csv, AgingSummary totals) {
        appendBuckets(
                csv,
                totals.getNotYetDue(),
                totals.getDays1To30(),
                totals.getDays31To60(),
                totals.getDays61To90(),
                totals.getDays90Plus(),
                totals.getOverdue(),
                totals.getTotalOutstanding());
    }

    private static void appendBuckets(
            StringBuilder csv,
            BigDecimal notYetDue,
            BigDecimal days1To30,
            BigDecimal days31To60,
            BigDecimal days61To90,
            BigDecimal days90Plus,
            BigDecimal overdue,
            BigDecimal totalOutstanding) {
        csv.append(amount(notYetDue))
                .append(',')
                .append(amount(days1To30))
                .append(',')
                .append(amount(days31To60))
                .append(',')
                .append(amount(days61To90))
                .append(',')
                .append(amount(days90Plus))
                .append(',')
                .append(amount(overdue))
                .append(',')
                .append(amount(totalOutstanding));
    }

    private static String amount(BigDecimal value) {
        return CsvFormat.amount(value);
    }
}
