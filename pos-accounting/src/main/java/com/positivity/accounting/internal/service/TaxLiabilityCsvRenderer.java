package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.TaxLiabilityReconciliation;
import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.dto.TaxLiabilityRow;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link TaxLiabilityReport} as deterministic CSV (story T8, issue #999).
 *
 * <p>Column order and row order are fixed: metadata block, per-jurisdiction rows in
 * the order produced by the report (state → county → city → special, then code), a
 * TOTAL row, and a reconciliation block. All figures are emitted with
 * {@link BigDecimal#toPlainString()} so the CSV matches the JSON report to the cent.
 * The volatile {@code generatedAt} timestamp is intentionally excluded so identical
 * report data always renders byte-identical CSV.
 */
@Component
public class TaxLiabilityCsvRenderer {

    private static final String ROW_HEADER = "Jurisdiction Type,Jurisdiction Code,Jurisdiction Name,Taxable Base,"
            + "Exempt Base,Exemption Reasons,Tax Collected Gross,Credits Netted,Net Tax";
    private static final String RECONCILIATION_HEADER =
            "Tax Payable Account,GL Net Activity,Report Net Tax,Unattributed Credits,Drift,Reconciled";

    /**
     * Render the report to CSV.
     *
     * @param report the generated tax-liability report
     * @return CSV text with CRLF-free Unix line endings and a trailing newline
     */
    @NonNull
    public String render(@NonNull TaxLiabilityReport report) {
        StringBuilder csv = new StringBuilder();

        csv.append("Report,Start Date,End Date\n");
        csv.append("TAX_LIABILITY,")
                .append(report.getStartDate())
                .append(',')
                .append(report.getEndDate())
                .append("\n\n");

        csv.append(ROW_HEADER).append('\n');
        for (TaxLiabilityRow row : report.getRows()) {
            csv.append(escapeText(row.getJurisdictionType()))
                    .append(',')
                    .append(escapeText(row.getJurisdictionCode()))
                    .append(',')
                    .append(escapeText(row.getJurisdictionName()))
                    .append(',')
                    .append(amount(row.getTaxableBase()))
                    .append(',')
                    .append(amount(row.getExemptBase()))
                    .append(',')
                    .append(escapeText(joinReasons(row.getExemptionReasons())))
                    .append(',')
                    .append(amount(row.getTaxCollectedGross()))
                    .append(',')
                    .append(amount(row.getCreditsNetted()))
                    .append(',')
                    .append(amount(row.getNetTax()))
                    .append('\n');
        }
        csv.append("TOTAL,,,")
                .append(amount(report.getTotalTaxableBase()))
                .append(',')
                .append(amount(report.getTotalExemptBase()))
                .append(",,")
                .append(amount(report.getTotalTaxCollectedGross()))
                .append(',')
                .append(amount(report.getTotalCreditsNetted()))
                .append(',')
                .append(amount(report.getTotalNetTax()))
                .append("\n\n");

        TaxLiabilityReconciliation reconciliation = report.getReconciliation();
        csv.append(RECONCILIATION_HEADER).append('\n');
        csv.append(escapeText(reconciliation.getTaxPayableAccountCode()))
                .append(',')
                .append(amount(reconciliation.getGlNetActivity()))
                .append(',')
                .append(amount(reconciliation.getReportNetTax()))
                .append(',')
                .append(amount(reconciliation.getUnattributedCredits()))
                .append(',')
                .append(amount(reconciliation.getDrift()))
                .append(',')
                .append(reconciliation.getReconciled())
                .append('\n');

        return csv.toString();
    }

    private static String amount(BigDecimal value) {
        return CsvFormat.amount(value);
    }

    private static String joinReasons(List<String> reasons) {
        return (reasons == null || reasons.isEmpty()) ? "" : String.join("|", reasons);
    }

    private static String escapeText(String value) {
        return CsvFormat.escapeText(value);
    }
}
