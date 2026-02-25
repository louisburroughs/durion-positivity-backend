package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.dto.consumption.ConsumptionResponse;
import org.jspecify.annotations.NonNull;

public interface ConsumptionService {

    @NonNull
    ConsumptionResponse consumePickedItems(@NonNull ConsumeItemsRequest request);
}
