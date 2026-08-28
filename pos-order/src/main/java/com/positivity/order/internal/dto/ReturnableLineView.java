package com.positivity.order.internal.dto;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Per-line returnability for a completed order (parity story F1, #1086, spec R5.4): the remaining
 * quantity a customer may still return, respecting the {@code returnable} flag.
 *
 * @param orderLineId the sold line
 * @param itemSku item SKU
 * @param soldQty units originally sold on the line
 * @param alreadyReturned Σ returnQty booked across non-cancelled returns
 * @param returnableQty soldQty − alreadyReturned when the line is returnable, else 0
 * @param returnable whether the line may be returned at all (workorder-consumed lines without the
 *     imported returnable flag are not returnable here — warranty returns route to pos-warranty)
 */
public record ReturnableLineView(
        @NonNull UUID orderLineId,
        @NonNull String itemSku,
        int soldQty,
        int alreadyReturned,
        int returnableQty,
        boolean returnable) {}
