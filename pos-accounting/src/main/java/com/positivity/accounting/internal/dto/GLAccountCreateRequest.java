package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.AccountType;

import java.time.LocalDate;

/**
 * DTO for creating a new GL Account.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLAccount Request</a>
 */
public class GLAccountCreateRequest {

    private String accountCode;
    private String accountName;
    private AccountType accountType;
    private String description;
    private String parentAccountId;
    private LocalDate activationDate;

    // Constructors
    public GLAccountCreateRequest() {
    }

    // Getters and Setters
    public String getAccountCode() {
        return accountCode;
    }

    public void setAccountCode(String accountCode) {
        this.accountCode = accountCode;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(AccountType accountType) {
        this.accountType = accountType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getParentAccountId() {
        return parentAccountId;
    }

    public void setParentAccountId(String parentAccountId) {
        this.parentAccountId = parentAccountId;
    }

    public LocalDate getActivationDate() {
        return activationDate;
    }

    public void setActivationDate(LocalDate activationDate) {
        this.activationDate = activationDate;
    }
}
