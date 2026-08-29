package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * One user-to-person link.
 *
 * <p>The user is named rather than identified, because usernames are this service's own key and a
 * caller should not have to look one up per row. The person id comes from pos-people, which is the
 * only service that can resolve an employee number to it.
 */
@Schema(description = "One link between a user account and its canonical person")
public record UserPersonLinkBulkIngestRecord(
        @Schema(description = "Login name of the account to link", example = "jane.doe")
        @NotBlank(message = "username is required")
        String username,

        @Schema(
                description = "Canonical person the account belongs to",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @NotNull(message = "personId is required")
        UUID personId) {}
