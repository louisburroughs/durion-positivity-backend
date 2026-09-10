package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Create the customer account that will own tenants (ADR-0062 §7). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for creating an account")
public class AccountCreateRequest {

    @Schema(description = "Registered legal name", example = "Acme Tire & Auto LLC", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 200)
    private String legalName;

    @Schema(description = "Trading name, when different", example = "Acme Tire", requiredMode = NOT_REQUIRED)
    @Size(max = 200)
    private String tradingName;

    @Schema(description = "Tax identifier", example = "12-3456789", requiredMode = NOT_REQUIRED)
    @Size(max = 64)
    private String taxId;

    @Schema(description = "ISO 3166-1 alpha-2 home country", example = "US", requiredMode = REQUIRED)
    @NotBlank
    @Pattern(regexp = "^[A-Z]{2}$")
    private String homeCountry;

    @Schema(description = "ISO 4217 home currency", example = "USD", requiredMode = REQUIRED)
    @NotBlank
    @Pattern(regexp = "^[A-Z]{3}$")
    private String homeCurrency;
}
