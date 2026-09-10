package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An account's billing profile. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "An account's billing profile")
public class BillingProfileResponse {

    @Schema(description = "Billing profile id", requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "Owning account id", requiredMode = REQUIRED)
    private UUID accountId;

    @Schema(description = "Billing address line 1", requiredMode = REQUIRED)
    private String addressLine1;

    @Schema(description = "Billing address line 2", requiredMode = NOT_REQUIRED)
    private String addressLine2;

    @Schema(description = "City", requiredMode = REQUIRED)
    private String city;

    @Schema(description = "State, province or region", requiredMode = NOT_REQUIRED)
    private String region;

    @Schema(description = "Postal code", requiredMode = NOT_REQUIRED)
    private String postalCode;

    @Schema(description = "ISO 3166-1 alpha-2 country", requiredMode = REQUIRED)
    private String country;

    @Schema(description = "Payment terms", requiredMode = REQUIRED)
    private String paymentTerms;

    @Schema(description = "Where invoices are sent", requiredMode = REQUIRED)
    private String invoicingEmail;

    @Schema(description = "Whether a payment processor customer token is on file", requiredMode = REQUIRED)
    private boolean paymentProcessorCustomerTokenPresent;

    @Schema(description = "Created at (ISO 8601)", requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(description = "Last changed at (ISO 8601)", requiredMode = REQUIRED)
    private Instant updatedAt;
}
