package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.reallocation.ReallocateRequest;
import com.positivity.inventory.internal.dto.reallocation.ReallocateResponse;
import org.jspecify.annotations.NonNull;

public interface AllocationReallocationService {
    @NonNull
    ReallocateResponse reallocate(@NonNull ReallocateRequest request);
}
