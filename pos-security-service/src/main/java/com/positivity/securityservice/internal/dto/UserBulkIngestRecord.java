package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

/**
 * One user account to provision.
 *
 * <p>Deliberately carries no password. A bulk file is uploaded and stored, so any password in it
 * exists at rest for as long as the upload does; the account is created with a generated password
 * instead, and whoever is to use it goes through the ordinary reset path.
 */
@Schema(description = "One user account to provision, without password material")
public record UserBulkIngestRecord(
        @Schema(description = "Unique login name", example = "jane.doe") @NotBlank(message = "username is required")
        String username,

        @Schema(description = "Existing role names to attach", example = "[\"TECHNICIAN\"]")
        @NotEmpty(message = "at least one role is required")
        Set<@NotBlank String> roles) {}
