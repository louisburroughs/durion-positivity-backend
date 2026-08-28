package com.positivity.inventory.internal.config;

import com.positivity.inventory.internal.dto.picklist.GeneratePickListRequest;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface PickListGenerationService {

    @NonNull
    PickListResponse generatePickList(@NonNull GeneratePickListRequest request);

    @NonNull
    PickListResponse getPickList(@NonNull UUID pickListId);
}
