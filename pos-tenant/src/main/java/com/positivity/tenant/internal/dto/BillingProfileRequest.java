package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Create or replace an account's billing profile. Never carries a card number. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for an account's billing profile")
public class BillingProfileRequest {

    @Schema(description = "Billing address line 1", example = "100 Main St", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 200)
    private String addressLine1;

    @Schema(description = "Billing address line 2", requiredMode = NOT_REQUIRED)
    @Size(max = 200)
    private String addressLine2;

    @Schema(description = "City", example = "Springfield", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 100)
    private String city;

    @Schema(description = "State, province or region", example = "IL", requiredMode = NOT_REQUIRED)
    @Size(max = 100)
    private String region;

    @Schema(description = "Postal code", example = "62704", requiredMode = NOT_REQUIRED)
    @Size(max = 20)
    private String postalCode;

    @Schema(description = "ISO 3166-1 alpha-2 country", example = "US", requiredMode = REQUIRED)
    @NotBlank
    @Pattern(regexp = "^[A-Z]{2}$")
    private String country;

    @Schema(description = "Payment terms", example = "NET30", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 32)
    private String paymentTerms;

    @Schema(description = "Where invoices are sent", example = "billing@acme.example", requiredMode = REQUIRED)
    @NotBlank
    @Email
    @Size(max = 320)
    private String invoicingEmail;

    @Schema(
            description = "Opaque customer token issued by the payment processor; never a card number",
            requiredMode = NOT_REQUIRED)
    @Size(max = 128)
    private String paymentProcessorCustomerToken;
}
