package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.ItemCostAuditDto;
import com.positivity.catalog.internal.dto.ItemCostsDto;
import com.positivity.catalog.internal.dto.PurchaseOrderReceivedEventDto;
import com.positivity.catalog.internal.dto.UpdateStandardCostRequestDto;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface ItemCostService {

    ItemCostsDto updateStandardCost(@NonNull UUID itemId, @NonNull UpdateStandardCostRequestDto request,
            @NonNull String actor);

    ItemCostsDto getItemCosts(@NonNull UUID itemId);

    List<ItemCostAuditDto> getAuditHistory(@NonNull UUID itemId);

    void processPoReceivedEvent(@NonNull PurchaseOrderReceivedEventDto event);
}
