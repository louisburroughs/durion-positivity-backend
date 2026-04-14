package com.positivity.customer.internal.dto.snapshot;

import org.jspecify.annotations.NonNull;

/**
 * Account summary in CRM snapshot.
 * CAP:092 - Story #99
 */
public class AccountSummary {
    @NonNull
    private String partyId;

    @NonNull
    private String accountNumber;

    @NonNull
    private String accountName;

    @NonNull
    private String accountType;

    public AccountSummary() {}

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
