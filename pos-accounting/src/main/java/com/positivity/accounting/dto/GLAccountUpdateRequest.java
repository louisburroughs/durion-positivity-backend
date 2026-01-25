package com.positivity.accounting.dto;

/**
 * DTO for updating an existing GL Account.
 * 
 * Only accountName and description can be updated.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLAccount Request</a>
 */
public class GLAccountUpdateRequest {

    private String accountName;
    private String description;

    // Constructors
    public GLAccountUpdateRequest() {
    }

    // Getters and Setters
    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
