package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
@Builder
@Schema(description = "Lifecycle and lock state of a user account")
public class AccountStateResponse {
    @Schema(description = "User identifier", example = "01960003-0000-7000-8000-000000000001", requiredMode = REQUIRED)
    UUID userId;

    @Schema(description = "True when the account is enabled", example = "true", requiredMode = REQUIRED)
    boolean enabled;

    @Schema(description = "True when the account is not locked", example = "true", requiredMode = REQUIRED)
    boolean accountNonLocked;

    @Schema(description = "True when the account has not expired", example = "true", requiredMode = REQUIRED)
    boolean accountNonExpired;

    @Schema(description = "True when the credentials have not expired", example = "true", requiredMode = REQUIRED)
    boolean credentialsNonExpired;

    @Schema(description = "Number of consecutive failed login attempts", example = "0", requiredMode = REQUIRED)
    int failedLoginAttempts;

    @Nullable
    @Schema(
            description = "Timestamp at which the account was locked",
            example = "2026-06-01T12:00:00Z",
            requiredMode = NOT_REQUIRED)
    Instant lockedAt;

    @Nullable
    @Schema(
            description = "Timestamp until which the account remains locked",
            example = "2026-06-01T12:30:00Z",
            requiredMode = NOT_REQUIRED)
    Instant lockedUntil;

    @Nullable
    @Schema(
            description = "Timestamp at which the account was disabled",
            example = "2026-05-01T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    Instant disabledAt;

    @Nullable
    @Schema(description = "Actor that disabled the account", example = "admin", requiredMode = NOT_REQUIRED)
    String disabledBy;

    @Nullable
    @Schema(
            description = "Timestamp at which the account expires",
            example = "2027-01-01T00:00:00Z",
            requiredMode = NOT_REQUIRED)
    Instant accountExpiresAt;

    @Nullable
    @Schema(
            description = "Timestamp at which the credentials expire",
            example = "2026-12-01T00:00:00Z",
            requiredMode = NOT_REQUIRED)
    Instant credentialsExpireAt;
}
