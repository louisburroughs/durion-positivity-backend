package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.SupplierItemCostCreateRequestDto;
import com.positivity.catalog.internal.dto.SupplierItemCostDto;
import com.positivity.catalog.internal.dto.SupplierItemCostUpdateRequestDto;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface SupplierItemCostService {

    SupplierItemCostDto createCostStructure(@NonNull SupplierItemCostCreateRequestDto request);

    SupplierItemCostDto getCostStructure(@NonNull UUID id);

    SupplierItemCostDto updateCostStructure(@NonNull UUID id, @NonNull SupplierItemCostUpdateRequestDto request);

    void deleteCostStructure(@NonNull UUID id);
}
