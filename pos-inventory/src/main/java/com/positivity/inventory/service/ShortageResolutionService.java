package com.positivity.inventory.service;

import org.jspecify.annotations.NonNull;

import com.positivity.inventory.internal.dto.shortage.ShortageResolutionRequest;
import com.positivity.inventory.internal.dto.shortage.ShortageResolutionResponse;

public interface ShortageResolutionService {
    @NonNull
    ShortageResolutionResponse resolveShortage(@NonNull ShortageResolutionRequest request);
}