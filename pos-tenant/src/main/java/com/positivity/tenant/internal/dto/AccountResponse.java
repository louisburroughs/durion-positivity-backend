package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.tenant.internal.enums.AccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An account with its contacts and billing profile. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A customer account that owns tenants")
public class AccountResponse {

    @Schema(description = "Account id", requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "Registered legal name", requiredMode = REQUIRED)
    private String legalName;

    @Schema(description = "Trading name", requiredMode = NOT_REQUIRED)
    private String tradingName;

    @Schema(description = "Lifecycle status", requiredMode = REQUIRED)
    private AccountStatus status;

    @Schema(description = "Tax identifier", requiredMode = NOT_REQUIRED)
    private String taxId;

    @Schema(description = "ISO 3166-1 alpha-2 home country", requiredMode = REQUIRED)
    private String homeCountry;

    @Schema(description = "ISO 4217 home currency", requiredMode = REQUIRED)
    private String homeCurrency;

    @Schema(description = "Contacts on the account", requiredMode = REQUIRED)
    private List<AccountContactResponse> contacts;

    @Schema(description = "Billing profile, when one has been set", requiredMode = NOT_REQUIRED)
    private BillingProfileResponse billingProfile;

    @Schema(description = "Ids of the tenants this account owns", requiredMode = REQUIRED)
    private List<UUID> tenantIds;

    @Schema(description = "Created at (ISO 8601)", requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(description = "Last changed at (ISO 8601)", requiredMode = REQUIRED)
    private Instant updatedAt;
}
