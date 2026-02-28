package com.positivity.shopmanager.service;

import com.positivity.shopmanager.service.dto.MechanicAvailabilityResult;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

public interface MechanicAvailabilityService {
    @NonNull MechanicAvailabilityResult queryAvailability(
            @NonNull String personId, @NonNull Instant windowStart, @NonNull Instant windowEnd);
}
