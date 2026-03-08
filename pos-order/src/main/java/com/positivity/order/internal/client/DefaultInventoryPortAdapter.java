package com.positivity.order.internal.client;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Default (stub) implementation of InventoryPort.
 * Always reports inventory as sufficient.
 * Replace with pos-inventory REST adapter in a future story.
 */
@Component
public class DefaultInventoryPortAdapter implements InventoryPort {

    @Override
    public @NonNull InventoryResult checkAvailability(@NonNull String itemSku, int quantity) {
        return new InventoryResult(true, Integer.MAX_VALUE);
    }
}
