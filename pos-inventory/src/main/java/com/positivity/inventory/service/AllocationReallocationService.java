package com.positivity.inventory.service;

import com.positivity.inventory.dto.reallocation.ReallocateRequest;
import com.positivity.inventory.dto.reallocation.ReallocateResponse;
import org.jspecify.annotations.NonNull;

public interface AllocationReallocationService {
    @NonNull
    ReallocateResponse reallocate(@NonNull ReallocateRequest request);
}
