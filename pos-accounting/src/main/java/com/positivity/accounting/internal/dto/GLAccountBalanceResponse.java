package com.positivity.accounting.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for GL Account balance.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GLAccountBalanceResponse {

    private UUID glAccountId;
    private String accountCode;
    private String accountName;
    private BigDecimal balance;
    private Instant asOfDate;
}
