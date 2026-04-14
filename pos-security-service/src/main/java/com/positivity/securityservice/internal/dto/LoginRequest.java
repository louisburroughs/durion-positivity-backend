package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for user-facing authentication at POST /v1/auth/login.
 *
 * @since 1.0
 * @see com.positivity.securityservice.internal.controller.AuthController#login
 */
@Schema(description = "Request to authenticate a user with username and password")
public record LoginRequest(
        @JsonProperty("username")
        @NotBlank
        @Schema(
                description = "The user's login username",
                example = "jane.doe",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String username,

        @JsonProperty("password")
        @NotBlank
        @Schema(description = "The user's login password", requiredMode = Schema.RequiredMode.REQUIRED)
        String password) {}
