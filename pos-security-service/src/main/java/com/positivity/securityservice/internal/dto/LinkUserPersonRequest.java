package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for linking a user account to its canonical person
 * (PUT /v1/users/{id}/person-link).
 */
public record LinkUserPersonRequest(
        @Schema(
                description = "Canonical person id (pos-people-contact) to link the account to",
                example = "01960011-0000-7000-8000-000000000001")
        @NotNull(message = "personId is required")
        UUID personId) {}
