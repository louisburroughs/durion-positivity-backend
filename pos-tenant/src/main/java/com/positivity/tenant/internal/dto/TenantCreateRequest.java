package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Register a tenant under an account (ADR-0062 §7). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for registering a tenant")
public class TenantCreateRequest {

    @Schema(
            description = "URL-safe unique name; lowercase letters, digits and hyphens, 3 to 63 characters",
            example = "acme-tire",
            requiredMode = REQUIRED)
    @NotBlank
    @Pattern(regexp = "^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$")
    private String slug;

    @Schema(description = "Human-readable name", example = "Acme Tire & Auto", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 200)
    private String displayName;

    @Schema(description = "Owning account id", requiredMode = REQUIRED)
    @NotNull
    private UUID accountId;

    @Schema(
            description = "Cell or region the tenant is served from",
            example = "us-east-1",
            requiredMode = NOT_REQUIRED)
    @Size(max = 64)
    private String cell;

    @Schema(
            description = "Email of the initial administrator pos-security-service creates while provisioning",
            example = "owner@acme.example",
            requiredMode = REQUIRED)
    @NotBlank
    @Email
    @Size(max = 320)
    private String initialAdminEmail;
}
