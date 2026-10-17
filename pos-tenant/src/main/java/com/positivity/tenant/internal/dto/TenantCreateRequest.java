package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.positivity.tenant.internal.validation.SupportedLocale;
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

    @Schema(description = """
                    Human-readable name users pick this organization by when signing in; unique across the \
                    registry, case- and whitespace-insensitively. Omit it and the owning account's legal name is \
                    used, suffixed " #2", " #3" and so on only where that would collide — a fallback, not the \
                    intended outcome, so supply a name that distinguishes this tenant from the account's others.""", example = "Acme Tire & Auto — Tucson", requiredMode = NOT_REQUIRED)
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

    @Schema(
            description = "The shop's locale as a BCP 47 tag with a region (en-US, es-US, es-MX, fr-CA, fr-FR by"
                    + " default; pos.tenant.supported-locales); case is normalised (fr-ca becomes fr-CA). A bare"
                    + " language or an unlisted tag is 400 VALIDATION_ERROR with fieldErrors[locale]",
            example = "en-US",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 35)
    @SupportedLocale
    private String locale;

    @Schema(
            description = "The reading tier the tenant starts on (CAP:550 S45); STANDARD when omitted. Must name an"
                    + " active tier, else 422 READING_TIER_NOT_ASSIGNABLE",
            example = "STANDARD",
            requiredMode = NOT_REQUIRED)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$")
    private String readingTierCode;
}
