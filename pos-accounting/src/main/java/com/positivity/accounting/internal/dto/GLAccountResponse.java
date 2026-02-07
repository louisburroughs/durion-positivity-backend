package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.AccountType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for GL Account response with computed status.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLAccount Response</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GLAccountResponse {

    private UUID glAccountId;
    private String accountCode;
    private String accountName;
    private AccountType accountType;
    private String description;
    private UUID parentAccountId;
    private LocalDateTime activationDate;
    private LocalDateTime deactivationDate;
    private String status; // ACTIVE, INACTIVE, NOT_YET_ACTIVE (derived)
    private Instant createdAt;
    private String createdBy;
    private Instant modifiedAt;
    private String modifiedBy;
    private Integer version;
}
