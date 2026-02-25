package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.returns.ReturnItemsRequest;
import com.positivity.inventory.internal.dto.returns.ReturnResponse;
import org.jspecify.annotations.NonNull;

public interface ReturnService {

    @NonNull
    ReturnResponse returnItemsToStock(@NonNull ReturnItemsRequest request);
}
