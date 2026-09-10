package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.positivity.tenant.internal.enums.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Partial update of an account; every field is optional and null leaves the value unchanged. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for updating an account")
public class AccountUpdateRequest {

    @Schema(description = "Registered legal name", requiredMode = NOT_REQUIRED)
    @Size(min = 1, max = 200)
    private String legalName;

    @Schema(description = "Trading name", requiredMode = NOT_REQUIRED)
    @Size(max = 200)
    private String tradingName;

    @Schema(description = "Lifecycle status", requiredMode = NOT_REQUIRED)
    private AccountStatus status;

    @Schema(description = "Tax identifier", requiredMode = NOT_REQUIRED)
    @Size(max = 64)
    private String taxId;

    @Schema(description = "ISO 3166-1 alpha-2 home country", requiredMode = NOT_REQUIRED)
    @Pattern(regexp = "^[A-Z]{2}$")
    private String homeCountry;

    @Schema(description = "ISO 4217 home currency", requiredMode = NOT_REQUIRED)
    @Pattern(regexp = "^[A-Z]{3}$")
    private String homeCurrency;
}
