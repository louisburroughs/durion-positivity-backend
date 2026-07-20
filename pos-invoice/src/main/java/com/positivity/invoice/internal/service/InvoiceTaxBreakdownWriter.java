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
class InvoiceTaxBreakdownWriter {

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
    void replace(@NonNull UUID invoiceId, @Nullable TaxCalculationResponse response) {
        lineTaxRepository.deleteByInvoiceId(invoiceId);
        taxSummaryRepository.deleteByInvoiceId(invoiceId);
        if (response == null || response.getLineItemTaxes() == null) {
            return;
        }

        List<InvoiceLineTax> lineRows = new ArrayList<>();
        Map<String, InvoiceTaxSummary> summaries = new LinkedHashMap<>();

        for (LineItemTax line : response.getLineItemTaxes()) {
            if (line.getJurisdictions() == null) {
                continue;
            }
            BigDecimal taxableBase = scale(line.getSubtotal());
            for (JurisdictionTax j : line.getJurisdictions()) {
                String type = j.getJurisdictionType() == null
                        ? null
                        : j.getJurisdictionType().code();
                // jurisdiction_type/code/rate are NOT NULL in the schema. A row missing any of
                // them cannot be persisted; skip it defensively rather than fail the whole
                // finalization (pos-tax always populates these for real tax rows).
                if (type == null || j.getCode() == null || j.getRate() == null) {
                    log.warn(
                            "Skipping tax breakdown row for invoice {} line {}: incomplete jurisdiction"
                                    + " identity (type={}, code={}, rate={})",
                            invoiceId,
                            line.getLineItemId(),
                            type,
                            j.getCode(),
                            j.getRate());
                    continue;
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

                String key = type + "|" + j.getCode();
                InvoiceTaxSummary summary = summaries.computeIfAbsent(
                        key,
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
        }

        if (!lineRows.isEmpty()) {
            lineTaxRepository.saveAll(lineRows);
            taxSummaryRepository.saveAll(new ArrayList<>(summaries.values()));
        }
    }

    @NonNull
    private static BigDecimal scale(@Nullable BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
