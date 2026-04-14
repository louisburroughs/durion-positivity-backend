package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.GLAccountStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Tolerate;

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
    private GLAccountStatus status; // ACTIVE, INACTIVE, NOT_YET_ACTIVE (derived)

    @Tolerate
    public void setStatus(String status) {
        if (status != null) {
            try {
                this.status = GLAccountStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                this.status = null;
            }
        }
    }

    private Instant createdAt;
    private String createdBy;
    private Instant modifiedAt;
    private String modifiedBy;
    private Integer version;
}
