package com.positivity.domainevents.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Goods handed back against a receipt, published on {@code inventory.events.v1} (CAP:550 #2598, #2635; ADR-0044 §4,
 * ADR-0049 §3): a correction of a receipt that was keyed wrong, or a return to the vendor.
 *
 * <p>pos-inventory owns what left the shelf; pos-order owns what the vendor still owes. The fact states the kind
 * ({@code RECEIPT_CORRECTION} reopens the line from the net received quantity; {@code RETURN_TO_VENDOR} defaults
 * to credit and does not) and the order decides what it means for its own figures. There is no disposition field:
 * a replacement is a per-return choice made in pos-order by reopening the original line.
 *
 * <p>Like a receipt this fact is a delta, so the consumer guards on {@code eventId}. {@code quantityReturned} is
 * in base units (ADR-0055); {@code accruedAmount} is a decimal at the currency exponent of {@code currencyCode}
 * (ADR-0067 PC-5 (a)), the receipt's accrual for the returned units. Reason and cost fields are not on this fact.
 *
 * @param vendorReturnId  identity of the return
 * @param purchaseOrderId the order the returned goods were received against
 * @param returnKind      {@code RECEIPT_CORRECTION} or {@code RETURN_TO_VENDOR}
 * @param currencyCode    ISO 4217 code of every amount; the order's document currency
 * @param occurredAt      when the return was recorded
 * @param lines           what went back, per receipt line
 */
public record VendorReturnRecordedV1(
        @NonNull UUID vendorReturnId,
        @NonNull UUID purchaseOrderId,
        @NonNull String returnKind,
        @Nullable String currencyCode,
        @Nullable Instant occurredAt,
        @NonNull List<VendorReturnLine> lines) {

    public static final String EVENT_TYPE = "vendorreturn.recorded";
    public static final int SCHEMA_VERSION = 1;
    public static final String RECEIPT_CORRECTION = "RECEIPT_CORRECTION";
    public static final String RETURN_TO_VENDOR = "RETURN_TO_VENDOR";

    public VendorReturnRecordedV1 {
        Objects.requireNonNull(vendorReturnId, "vendorReturnId must not be null");
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId must not be null");
        Objects.requireNonNull(returnKind, "returnKind must not be null");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * One returned receipt line.
     *
     * @param receiptId        the receipt the goods came in on
     * @param receiptLineId    its line
     * @param poLineId         the purchase-order line the receipt line was applied to; null when the receipt named none
     * @param quantityReturned base units handed back
     * @param accruedAmount    the receipt's accrual for those units, a decimal at the currency exponent
     */
    public record VendorReturnLine(
            @Nullable UUID receiptId,
            @Nullable UUID receiptLineId,
            @Nullable UUID poLineId,
            @NonNull BigDecimal quantityReturned,
            @Nullable BigDecimal accruedAmount) {
        public VendorReturnLine {
            Objects.requireNonNull(quantityReturned, "quantityReturned must not be null");
        }
    }
}
