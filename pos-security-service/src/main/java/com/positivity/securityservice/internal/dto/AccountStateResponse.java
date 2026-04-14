package com.positivity.securityservice.internal.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
@Builder
public class AccountStateResponse {
    UUID userId;
    boolean enabled;
    boolean accountNonLocked;
    boolean accountNonExpired;
    boolean credentialsNonExpired;
    int failedLoginAttempts;

    @Nullable
    Instant lockedAt;

    @Nullable
    Instant lockedUntil;

    @Nullable
    Instant disabledAt;

    @Nullable
    String disabledBy;

    @Nullable
    Instant accountExpiresAt;

    @Nullable
    Instant credentialsExpireAt;
}
