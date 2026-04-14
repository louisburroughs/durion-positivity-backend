package com.positivity.price.service;

import com.positivity.price.internal.dto.RestrictionOverrideRequest;
import com.positivity.price.internal.dto.RestrictionOverrideResponse;
import org.jspecify.annotations.NonNull;

/**
 * Issues and audits restriction overrides.
 * Caller must hold pricing:restriction:override authority.
 * Actor identity resolved from SecurityContext per ADR-0018. Issue #43.
 */
public interface RestrictionOverrideService {

    @NonNull
    RestrictionOverrideResponse createOverride(@NonNull RestrictionOverrideRequest request);
}
