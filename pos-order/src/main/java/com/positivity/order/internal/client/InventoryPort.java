package com.positivity.order.internal.client;

import org.jspecify.annotations.NonNull;

public interface InventoryPort {
    @NonNull
    InventoryResult checkAvailability(@NonNull String itemSku, int quantity);
}
