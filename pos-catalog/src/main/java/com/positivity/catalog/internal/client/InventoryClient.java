package com.positivity.catalog.internal.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Client for the pos-inventory service availability query endpoint.
 * Calls GET /v1/inventory/availability/query for availability data.
 *
 * Issue: CAP-247 Story #16
 */
public interface InventoryClient {

    /**
     * Fetches availability for a product (by SKU) at a given location.
     *
     * @param productSku SKU of the product
     * @param locationId location/store identifier
     * @return Optional containing availability response, or empty if service is
     *         unavailable
     */
    Optional<AvailabilityClientResponse> fetchAvailability(String productSku, UUID locationId);

    /**
     * Local response record mirroring the relevant pos-inventory availability
     * fields.
     */
    record AvailabilityClientResponse(
            int onHandQuantity,
            int allocatedQuantity,
            int availableToPromiseQuantity,
            String unitOfMeasure) {
    }
}
