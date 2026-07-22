package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.client.TaxServiceClient;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.InvoiceItem;
import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxLineItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Computes an invoice's tax against its shop-location jurisdiction (extracted from
 * {@code InvoiceServiceImpl} for #985 so draft re-pricing and finalization share one
 * line-item/jurisdiction mapping).
 *
 * <p>Tax is computed on the invoice line items (the goods/services sold). Invoice-level
 * adjustments (discounts/fees) are applied to the total after tax and are not part of the
 * taxable base. When there is nothing taxable the tax service is not called and {@code null}
 * is returned.
 *
 * <p>Two intents (#985):
 * <ul>
 *   <li>{@link #calculateDraft(Invoice)} — a read-only re-price while DRAFT
 *       ({@code committable=false}); the provider does not persist a document.</li>
 *   <li>{@link #calculateCommittable(Invoice)} — the finalization-time calculation
 *       ({@code committable=true}, {@code referenceId == invoiceId}); the provider persists an
 *       uncommitted document under {@code code == invoiceId} that the subsequent lifecycle
 *       {@code commit(invoiceId)} promotes.</li>
 * </ul>
 */
@Component
public class InvoiceTaxCalculator {

    private final TaxServiceClient taxServiceClient;
    private final LocationReferenceService locationReferenceService;

    public InvoiceTaxCalculator(
            @NonNull TaxServiceClient taxServiceClient, @NonNull LocationReferenceService locationReferenceService) {
        this.taxServiceClient = taxServiceClient;
        this.locationReferenceService = locationReferenceService;
    }

    /**
     * Read-only draft re-price calculation ({@code committable=false}).
     *
     * @param invoice the DRAFT invoice being re-priced
     * @return the full tax response, or {@code null} when the invoice has no taxable line items
     */
    @Nullable
    public TaxCalculationResponse calculateDraft(@NonNull Invoice invoice) {
        return calculate(invoice, false, invoice.getWorkorderId());
    }

    /**
     * Finalization-time committable calculation (#985): {@code committable=true} with
     * {@code referenceId == invoiceId}, so the provider persists the tax document under the
     * code the lifecycle {@code commit}/{@code void} calls resolve by.
     *
     * @param invoice the invoice being finalized (still DRAFT)
     * @return the full tax response, or {@code null} when the invoice has no taxable line items
     *         (then no provider document exists and no lifecycle commit must be attempted)
     */
    @Nullable
    public TaxCalculationResponse calculateCommittable(@NonNull Invoice invoice) {
        UUID invoiceId =
                Objects.requireNonNull(invoice.getId(), "invoiceId is required for a committable tax calculation");
        return calculate(invoice, true, invoiceId);
    }

    @Nullable
    private TaxCalculationResponse calculate(
            @NonNull Invoice invoice, boolean committable, @Nullable UUID referenceId) {
        List<TaxLineItem> taxLineItems = buildTaxLineItems(invoice);
        if (taxLineItems.isEmpty()) {
            return null;
        }

        UUID locationId = invoice.getLocationId();
        if (locationId == null) {
            throw new IllegalStateException(
                    "invoice has taxable line items but no locationId to resolve the tax jurisdiction");
        }

        TaxAddress destination = locationReferenceService.resolveTaxAddress(locationId);
        return taxServiceClient.calculateTaxDetailed(taxLineItems, destination, referenceId, committable);
    }

    @NonNull
    private List<TaxLineItem> buildTaxLineItems(@NonNull Invoice invoice) {
        List<TaxLineItem> taxLineItems = new ArrayList<>();
        int index = 0;
        for (InvoiceItem item : invoice.getItems()) {
            BigDecimal quantity = safeMoney(item.getQuantity(), BigDecimal.ONE);
            BigDecimal unitPrice = safeMoney(item.getUnitPrice(), BigDecimal.ZERO);
            // pos-tax requires positive quantity and unit price; non-positive lines contribute
            // no tax, so skip them rather than tripping bean validation (400) on the request.
            if (quantity.signum() <= 0 || unitPrice.signum() <= 0) {
                continue;
            }
            taxLineItems.add(TaxLineItem.builder()
                    .lineItemId(String.valueOf(++index))
                    .description(item.getDescription())
                    .quantity(quantity)
                    .unitPrice(unitPrice)
                    .taxExempt(false)
                    .build());
        }
        return taxLineItems;
    }

    @NonNull
    private static BigDecimal safeMoney(@Nullable BigDecimal value, @NonNull BigDecimal fallback) {
        return (value != null ? value : fallback).setScale(4, RoundingMode.HALF_UP);
    }
}
