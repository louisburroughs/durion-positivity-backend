package com.positivity.inventory.service;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Handles {@code inventory.reservation.request-requested} commands (CAP #1315): the ATP-gate
 * commitment point for pos-order (checkout) and pos-workorder (part-issue).
 */
public interface ReservationRequestService {

    /**
     * Registers demand for one line and evaluates whether owned ATP at {@code locationId} covers
     * it, publishing a reservation-outcome fact either way.
     *
     * @param workorderLineId workorder line the demand is for, when demand is from a workorder
     * @param salesOrderLineId sales-order line the demand is for, when demand is from a sales order
     * @param stockItemId stock item requested
     * @param requiredQuantity quantity requested, in {@code uomCode} when given, otherwise already
     *     in the product's base unit; decimal-capable per the product's catalog divisibility
     *     declaration (ADR-0055, #1414)
     * @param locationId site the demand must be covered at
     * @param uomCode unit {@code requiredQuantity} is expressed in (ADR-0055 stage 3, #1415);
     *     {@code null} means the product's base unit. When given, converted to base with {@code
     *     DOWN} rounding before anything else runs — a reservation must never promise more than
     *     exists.
     */
    void handle(
            @Nullable UUID workorderLineId,
            @Nullable UUID salesOrderLineId,
            @NonNull UUID stockItemId,
            @NonNull BigDecimal requiredQuantity,
            @NonNull UUID locationId,
            @Nullable String uomCode);
}
