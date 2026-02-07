package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.AccountType;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for creating a new GL Account.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLAccount Request</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GLAccountCreateRequest {

    private String accountCode;
    private String accountName;
    private AccountType accountType;
    private String description;
    private UUID parentAccountId;
    private LocalDateTime activationDate;
}
