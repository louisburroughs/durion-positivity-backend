package com.positivity.order.internal.client;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * REST adapter on pos-inventory's availability API (plan story H1, replaces the always-available
 * stub). Availability is advisory (WARN_AND_BACKORDER policy): an unreachable inventory service
 * degrades to "sufficient" with a warning log rather than blocking cart work — stock truth is
 * reconciled by the order-completed consumption flow (story H2).
 */
@Component
@Slf4j
public class RestInventoryPortAdapter implements InventoryPort {

    /** Minimal projection of pos-inventory's AvailabilityView. */
    record AvailabilityView(int availableToPromiseQuantity) {}

    private final RestClient inventoryServiceRestClient;

    public RestInventoryPortAdapter(@Qualifier("inventoryServiceRestClient") RestClient inventoryServiceRestClient) {
        this.inventoryServiceRestClient = inventoryServiceRestClient;
    }

    @Override
    public @NonNull InventoryResult checkAvailability(@NonNull String itemSku, int quantity) {
        try {
            AvailabilityView view = inventoryServiceRestClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/inventory/availability/by-sku")
                            .queryParam("productSku", itemSku)
                            .build())
                    .header("X-User", "pos-order")
                    .header("X-Authorities", "inventory:on_hand:view")
                    .retrieve()
                    .body(AvailabilityView.class);
            if (view == null) {
                log.warn("pos-inventory returned an empty availability response for sku {}", itemSku);
                return new InventoryResult(true, quantity);
            }
            int atp = view.availableToPromiseQuantity();
            return new InventoryResult(atp >= quantity, atp);
        } catch (HttpClientErrorException.NotFound notFound) {
            // Unknown SKU at inventory: nothing on hand — flag as backorder.
            return new InventoryResult(false, 0);
        } catch (Exception e) {
            log.warn(
                    "pos-inventory unreachable for sku {}; treating availability as advisory: {}",
                    itemSku,
                    e.getMessage());
            return new InventoryResult(true, quantity);
        }
    }
}
