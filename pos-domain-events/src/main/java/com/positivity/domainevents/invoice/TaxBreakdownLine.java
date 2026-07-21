package com.positivity.domainevents.invoice;

import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One persisted per-line × per-jurisdiction tax row carried additively on
 * {@link InvoiceUpdatedV1#taxBreakdown()} (story T5b, ADR-0044 §3).
 *
 * <p>This is the event-contract projection of pos-invoice's {@code invoice_line_tax} rows, which
 * in turn mirror the per-line jurisdiction matrix produced by pos-tax
 * ({@code TaxCalculationResponse.LineItemTax.jurisdictions[]}). It is deliberately
 * dependency-light: {@code jurisdictionType} and {@code exemptionReasonCode} are plain
 * {@code String}s rather than pos-tax-common enums, so {@code pos-domain-events} need not depend
 * on {@code pos-tax-common}. Consumers that want the typed enums re-parse the codes.
 *
 * <p>Money is {@link BigDecimal} at currency scale (HALF_UP). The scalar
 * {@link InvoiceUpdatedV1#tax()} remains the denormalized rollup; where a full breakdown is
 * present the invariant {@code tax == Σ taxAmount} holds (decision D-T6).
 *
 * @param lineItemId          the invoice line identifier this row was priced from; {@code null}
 *                            for a summary-only row or when the producer could not attribute it
 * @param jurisdictionType    the jurisdiction type code (e.g. {@code STATE}, {@code COUNTY},
 *                            {@code CITY}, {@code DISTRICT})
 * @param jurisdictionCode    the taxing-authority code within that type
 * @param rate                the applied rate as a decimal fraction (e.g. {@code 0.0725} for
 *                            7.25%)
 * @param taxableBase         the base amount the rate was applied to for this line/jurisdiction
 * @param taxAmount           the calculated tax for this line/jurisdiction ({@code 0} on an
 *                            exempt zero-rate row)
 * @param exempt              whether this is a reportable zero-rate exempt row (story T3)
 * @param exemptionReasonCode the exemption reason echoed onto an exempt (or exemption-denied)
 *                            row; {@code null} for a normal taxed row
 */
public record TaxBreakdownLine(
        @Nullable String lineItemId,
        @NonNull String jurisdictionType,
        @NonNull String jurisdictionCode,
        @NonNull BigDecimal rate,
        @NonNull BigDecimal taxableBase,
        @NonNull BigDecimal taxAmount,
        boolean exempt,
        @Nullable String exemptionReasonCode) {}
