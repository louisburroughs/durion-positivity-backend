package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.entity.InvoiceLineTax;
import com.positivity.invoice.internal.entity.InvoiceTaxSummary;
import com.positivity.invoice.internal.repository.InvoiceLineTaxRepository;
import com.positivity.invoice.internal.repository.InvoiceTaxSummaryRepository;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxCalculationResponse.JurisdictionTax;
import com.positivity.tax.common.dto.TaxCalculationResponse.LineItemTax;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Materializes the persisted per-line × per-jurisdiction tax matrix (story T5a) for an invoice.
 *
 * <p>Rebuilds {@code invoice_line_tax} and its {@code invoice_tax_summary} rollup wholesale from a
 * {@link TaxCalculationResponse}: every existing row for the invoice is deleted and replaced, so a
 * DRAFT re-price is idempotent. Callers guard finalized invoices before invoking this (the freeze
 * lives in the service). Mechanical only — no state or lifecycle knowledge here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceTaxBreakdownWriter {

    private static final int MONEY_SCALE = 4;

    private final InvoiceLineTaxRepository lineTaxRepository;
    private final InvoiceTaxSummaryRepository taxSummaryRepository;

    /**
     * Replace the stored breakdown for {@code invoiceId} with the rows in {@code response}.
     *
     * @param invoiceId the invoice whose breakdown is rebuilt
     * @param response  the calculation response to materialize; {@code null} (nothing taxable)
     *                  clears the breakdown
     */
    public void replace(@NonNull UUID invoiceId, @Nullable TaxCalculationResponse response) {
        lineTaxRepository.deleteByInvoiceId(invoiceId);
        taxSummaryRepository.deleteByInvoiceId(invoiceId);
        if (response == null || response.getLineItemTaxes() == null) {
            return;
        }

        List<InvoiceLineTax> lineRows = new ArrayList<>();
        Map<String, InvoiceTaxSummary> summaries = new LinkedHashMap<>();
        for (LineItemTax line : response.getLineItemTaxes()) {
            addLineRows(invoiceId, line, lineRows, summaries);
        }

        if (!lineRows.isEmpty()) {
            lineTaxRepository.saveAll(lineRows);
            taxSummaryRepository.saveAll(new ArrayList<>(summaries.values()));
        }
    }

    /** Adds every jurisdiction row for one line item to {@code lineRows}/{@code summaries}. */
    private void addLineRows(
            @NonNull UUID invoiceId,
            @NonNull LineItemTax line,
            @NonNull List<InvoiceLineTax> lineRows,
            @NonNull Map<String, InvoiceTaxSummary> summaries) {
        if (line.getJurisdictions() == null) {
            return;
        }
        BigDecimal taxableBase = scale(line.getSubtotal());
        for (JurisdictionTax j : line.getJurisdictions()) {
            addJurisdictionRow(invoiceId, line, taxableBase, j, lineRows, summaries);
        }
    }

    /**
     * Appends one line-tax row and rolls it into the per-jurisdiction summary, unless the
     * jurisdiction identity is incomplete (see {@link #isIncompleteIdentity}), in which case the
     * row is skipped defensively instead of failing the whole finalization.
     */
    private void addJurisdictionRow(
            @NonNull UUID invoiceId,
            @NonNull LineItemTax line,
            @NonNull BigDecimal taxableBase,
            @NonNull JurisdictionTax j,
            @NonNull List<InvoiceLineTax> lineRows,
            @NonNull Map<String, InvoiceTaxSummary> summaries) {
        String type =
                j.getJurisdictionType() == null ? null : j.getJurisdictionType().code();
        if (isIncompleteIdentity(type, j)) {
            log.warn(
                    "Skipping tax breakdown row for invoice {} line {}: incomplete jurisdiction"
                            + " identity (type={}, code={}, rate={})",
                    invoiceId,
                    line.getLineItemId(),
                    type,
                    j.getCode(),
                    j.getRate());
            return;
        }

        String reason = j.getExemptionReasonCode() == null
                ? null
                : j.getExemptionReasonCode().name();
        BigDecimal amount = scale(j.getAmount());
        lineRows.add(InvoiceLineTax.builder()
                .invoiceId(invoiceId)
                .lineItemId(line.getLineItemId())
                .jurisdictionType(type)
                .jurisdictionCode(j.getCode())
                .rate(j.getRate())
                .taxableBase(taxableBase)
                .taxAmount(amount)
                .exempt(j.isExempt())
                .exemptionReasonCode(reason)
                .build());

        InvoiceTaxSummary summary = summaries.computeIfAbsent(
                type + "|" + j.getCode(),
                k -> InvoiceTaxSummary.builder()
                        .invoiceId(invoiceId)
                        .jurisdictionType(type)
                        .jurisdictionCode(j.getCode())
                        .taxableBase(BigDecimal.ZERO)
                        .taxAmount(BigDecimal.ZERO)
                        .build());
        summary.setTaxableBase(scale(summary.getTaxableBase().add(taxableBase)));
        summary.setTaxAmount(scale(summary.getTaxAmount().add(amount)));
    }

    /**
     * jurisdiction_type/code/rate are NOT NULL in the schema. A row missing any of them cannot be
     * persisted (pos-tax always populates these for real tax rows).
     */
    private static boolean isIncompleteIdentity(@Nullable String type, @NonNull JurisdictionTax j) {
        return type == null || j.getCode() == null || j.getRate() == null;
    }

    @NonNull
    private static BigDecimal scale(@Nullable BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
