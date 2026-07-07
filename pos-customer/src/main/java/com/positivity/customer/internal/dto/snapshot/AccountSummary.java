package com.positivity.customer.internal.dto.snapshot;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/**
 * Account summary in CRM snapshot.
 * CAP:092 - Story #99
 */
@Schema(description = "Account summary surfaced in a CRM snapshot")
public class AccountSummary {
    @Schema(
            description = "Canonical party identifier of the account",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private String partyId;

    @Schema(
            description = "Human-readable account/customer number",
            example = "CUST-000123",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private String accountNumber;

    @Schema(
            description = "Account display name",
            example = "Acme Industrial Supply, LLC",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private String accountName;

    @Schema(
            description = "Account type discriminator",
            example = "ORGANIZATION",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private String accountType;

    public AccountSummary(
            @NonNull String partyId,
            @NonNull String accountNumber,
            @NonNull String accountName,
            @NonNull String accountType) {
        this.partyId = partyId;
        this.accountNumber = accountNumber;
        this.accountName = accountName;
        this.accountType = accountType;
    }

    @NonNull
    public String getPartyId() {
        return partyId;
    }

    public void setPartyId(@NonNull String partyId) {
        this.partyId = partyId;
    }

    @NonNull
    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(@NonNull String accountNumber) {
        this.accountNumber = accountNumber;
    }

    @NonNull
    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(@NonNull String accountName) {
        this.accountName = accountName;
    }

    @NonNull
    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(@NonNull String accountType) {
        this.accountType = accountType;
    }
}
