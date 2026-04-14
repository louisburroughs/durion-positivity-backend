package com.positivity.price.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record RestrictionOverrideResponse(UUID overrideId, Instant expiresAt) {}
