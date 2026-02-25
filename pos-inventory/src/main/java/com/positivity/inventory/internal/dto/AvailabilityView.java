package com.positivity.inventory.internal.dto;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Read-only view of on-hand and available-to-promise quantities for a product
 * at a specific location scope.
 *
 * <p>
 * ATP = onHandQuantity - allocatedQuantity (ADR-0001)
 *
 * Issue: CAP-215 Story #36
 */
@Value
@Builder
public class AvailabilityView {

    /** Stock-keeping unit identifier. */
    String productSku;

    /** Location identifier for the scope of this availability view. */
    UUID locationId;

    /** Optional sub-location identifier (null when scoped to full location). */
    UUID storageLocationId;

    /** Net on-hand quantity (sum of affectsOnHand entries). */
    int onHandQuantity;

    /** Net allocated quantity (ALLOCATION_CREATED minus ALLOCATION_RELEASED). */
    int allocatedQuantity;

    /** ATP = onHandQuantity - allocatedQuantity. */
    int availableToPromiseQuantity;

    /** Unit of measure code (e.g. EACH, KG). */
    String unitOfMeasure;
}
