package com.positivity.accounting.internal.bankfeed.file.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The statement header entered with an import (SPEC §3.3, §4.2; story S3, #2302). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Statement header as printed by the bank, entered with the file")
public class BankImportStatementHeader {

    @Schema(description = "First day of the statement window", example = "2026-09-01", requiredMode = REQUIRED)
    private LocalDate startDate;

    @Schema(
            description = "Last day of the statement window; not in the future",
            example = "2026-09-30",
            requiredMode = REQUIRED)
    private LocalDate endDate;

    @Schema(description = "Balance printed at the start of the window", example = "12345.67", requiredMode = REQUIRED)
    private BigDecimal openingBalance;

    @Schema(description = "Balance printed at the end of the window", example = "12830.67", requiredMode = REQUIRED)
    private BigDecimal closingBalance;

    @Schema(description = "The bank's statement number, when printed", example = "2026-09")
    private String statementRef;
}
