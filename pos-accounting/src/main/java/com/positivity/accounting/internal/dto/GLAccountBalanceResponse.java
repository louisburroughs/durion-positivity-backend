package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for GL Account balance.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Balance of a GL account as of a point in time")
public class GLAccountBalanceResponse {

    @Schema(description = "Unique identifier of the GL account", example = "01960003-0000-7000-8000-000000000001", requiredMode = REQUIRED)
    private UUID glAccountId;

    @Schema(description = "Account code of the GL account", example = "4000", requiredMode = NOT_REQUIRED)
    private String accountCode;

    @Schema(description = "Account name of the GL account", example = "Service Revenue", requiredMode = NOT_REQUIRED)
    private String accountName;

    @Schema(description = "Balance of the account", example = "1250.00", requiredMode = REQUIRED)
    private BigDecimal balance;

    @Schema(description = "Point in time the balance is computed as of (ISO 8601)", example = "2026-06-18T08:00:00Z", requiredMode = REQUIRED)
    private Instant asOfDate;
}
