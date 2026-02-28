package com.positivity.inventory.service;

import com.positivity.inventory.dto.shortage.ShortageResolutionRequest;
import com.positivity.inventory.dto.shortage.ShortageResolutionResponse;
import org.jspecify.annotations.NonNull;

public interface ShortageResolutionService {
    @NonNull
    ShortageResolutionResponse resolveShortage(@NonNull ShortageResolutionRequest request);
}