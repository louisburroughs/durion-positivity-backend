package com.positivity.catalog.internal.client;

import java.math.BigDecimal;
import java.time.Instant;
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
     * Fetches dynamic lead time for a product at a given location.
     *
     * @param productId  product identifier
     * @param locationId location/store identifier
     * @return Optional containing lead-time response, or empty when unavailable
     */
    default Optional<LeadTimeClientResponse> fetchLeadTime(UUID productId, UUID locationId) {
        return Optional.empty();
    }

    /**
     * Local response record mirroring the relevant pos-inventory availability
     * fields.
     */
    record AvailabilityClientResponse(
            BigDecimal onHandQuantity,
            BigDecimal allocatedQuantity,
            BigDecimal availableToPromiseQuantity,
            String unitOfMeasure) {}

    /**
     * Local response record mirroring the lead-time fields returned by
     * pos-inventory.
     */
    record LeadTimeClientResponse(
            Integer minDays, Integer maxDays, String displayText, String source, String confidence, Instant asOf) {}
}
