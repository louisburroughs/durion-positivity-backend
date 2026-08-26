package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

/**
 * Request body for operator user provisioning (POST /v1/users). Replaces the
 * former untyped map payload so malformed requests fail with 400 field errors
 * instead of 500s.
 */
public record CreateUserRequest(
        @Schema(description = "Unique login name for the new account", example = "jane.doe")
        @NotBlank(message = "username is required")
        String username,

        @Schema(description = "Initial password; hashed server-side before storage", example = "Sup3rS3cret!")
        @NotBlank(message = "password is required")
        String password,

        @Schema(description = "Existing role names to attach directly", example = "[\"TECHNICIAN\"]")
        @NotEmpty(message = "at least one role is required")
        Set<@NotBlank String> roles) {}
