package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A justified exclude or restore of one bank transaction (SPEC §3.8, §6.1; story S2, #2301). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A justification (at least 10 characters) and, optionally, the row version the caller read")
public class BankTransactionJustificationRequest {

    @Schema(
            description = "Why (at least 10 characters)",
            example = "Internal transfer recorded twice by the bank",
            requiredMode = REQUIRED)
    private String justification;

    @Schema(description = "The row version the caller read; a stale one answers 409 OPTIMISTIC_LOCK")
    private Long version;
}
