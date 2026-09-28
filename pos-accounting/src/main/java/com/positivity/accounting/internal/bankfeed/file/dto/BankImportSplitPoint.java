package com.positivity.accounting.internal.bankfeed.file.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A point at which the file's window is split into two statements (SPEC §4.4, §5.7; story S3, #2302):
 * {@code date} is the last day of the earlier segment, {@code closingBalance} its closing balance keyed
 * from online banking (the file carries only the final one).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A split of the statement window: the last day of a segment and its keyed closing balance")
public class BankImportSplitPoint {

    @Schema(
            description = "Last day of the earlier segment; inside the window, before its end date",
            example = "2026-09-30",
            requiredMode = REQUIRED)
    private LocalDate date;

    @Schema(
            description = "Closing balance of the segment ending on date",
            example = "10500.00",
            requiredMode = REQUIRED)
    private BigDecimal closingBalance;
}
