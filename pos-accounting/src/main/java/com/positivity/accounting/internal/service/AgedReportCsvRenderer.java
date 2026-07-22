package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.AgedPayablesReport;
import com.positivity.accounting.internal.dto.AgedPayablesRow;
import com.positivity.accounting.internal.dto.AgedReceivablesReport;
import com.positivity.accounting.internal.dto.AgedReceivablesRow;
import com.positivity.accounting.internal.dto.AgingSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
 * defined by the report DTOs (current / 31-60 / 61-90 / 90+), each row's total,
 * and a grand-total row.
 * All figures are emitted with {@link BigDecimal#toPlainString()} so the CSV
 * matches the JSON report to the cent. The volatile {@code generatedAt}
 * timestamp is intentionally excluded so identical report data always renders
 * byte-identical CSV.
 */
@Component
public class AgedReportCsvRenderer {

    private static final String BUCKET_COLUMNS = "Current (0-30),31-60 Days,61-90 Days,90+ Days,Total Outstanding";

    /**
     * Render the aged receivables report to CSV.
     *
     * @param report the generated aged AR report
     * @return CSV text with Unix line endings and a trailing newline
     */
    @NonNull
    public String render(@NonNull AgedReceivablesReport report) {
        List<PartyAgingRow> rows =
                report.getRows().stream().map(AgedReportCsvRenderer::toPartyRow).toList();
        return renderAging(
                "AGED_RECEIVABLES", "Customer ID,Customer Name", report.getAsOfDate(), rows, report.getTotals());
    }

    /**
     * Render the aged payables report to CSV.
     *
     * @param report the generated aged AP report
     * @return CSV text with Unix line endings and a trailing newline
     */
    @NonNull
    public String render(@NonNull AgedPayablesReport report) {
        List<PartyAgingRow> rows =
                report.getRows().stream().map(AgedReportCsvRenderer::toPartyRow).toList();
        return renderAging("AGED_PAYABLES", "Vendor ID,Vendor Name", report.getAsOfDate(), rows, report.getTotals());
    }

    private static PartyAgingRow toPartyRow(AgedReceivablesRow row) {
        return new PartyAgingRow(
                row.getCustomerId().toString(),
                row.getCustomerName(),
                row.getCurrent(),
                row.getDays31To60(),
                row.getDays61To90(),
                row.getDays90Plus(),
                row.getTotalOutstanding());
    }

    private static PartyAgingRow toPartyRow(AgedPayablesRow row) {
        return new PartyAgingRow(
                row.getVendorId().toString(),
                row.getVendorName(),
                row.getCurrent(),
                row.getDays31To60(),
                row.getDays61To90(),
                row.getDays90Plus(),
                row.getTotalOutstanding());
    }

    private static String renderAging(
            String reportKey, String partyHeader, LocalDate asOfDate, List<PartyAgingRow> rows, AgingSummary totals) {
        StringBuilder csv = new StringBuilder();

        csv.append("Report,As-Of Date\n");
        csv.append(reportKey).append(',').append(asOfDate).append("\n\n");

        csv.append(partyHeader).append(',').append(BUCKET_COLUMNS).append('\n');
        for (PartyAgingRow row : rows) {
            csv.append(CsvFormat.escapeText(row.partyId()))
                    .append(',')
                    .append(CsvFormat.escapeText(row.partyName()))
                    .append(',')
                    .append(amount(row.current()))
                    .append(',')
                    .append(amount(row.days31To60()))
                    .append(',')
                    .append(amount(row.days61To90()))
                    .append(',')
                    .append(amount(row.days90Plus()))
                    .append(',')
                    .append(amount(row.totalOutstanding()))
                    .append('\n');
        }
        csv.append("TOTAL,,")
                .append(amount(totals.getCurrent()))
                .append(',')
                .append(amount(totals.getDays31To60()))
                .append(',')
                .append(amount(totals.getDays61To90()))
                .append(',')
                .append(amount(totals.getDays90Plus()))
                .append(',')
                .append(amount(totals.getTotalOutstanding()))
                .append('\n');

        return csv.toString();
    }

    private static String amount(BigDecimal value) {
        return CsvFormat.amount(value);
    }

    /** Common shape of one aged AR/AP row: party identity plus the aging buckets. */
    private record PartyAgingRow(
            String partyId,
            String partyName,
            BigDecimal current,
            BigDecimal days31To60,
            BigDecimal days61To90,
            BigDecimal days90Plus,
            BigDecimal totalOutstanding) {}
}
