package com.positivity.inventory.service;

import org.jspecify.annotations.NonNull;

import com.positivity.inventory.internal.dto.reallocation.ReallocateRequest;
import com.positivity.inventory.internal.dto.reallocation.ReallocateResponse;

public interface AllocationReallocationService {
    @NonNull
    ReallocateResponse reallocate(@NonNull ReallocateRequest request);
}
