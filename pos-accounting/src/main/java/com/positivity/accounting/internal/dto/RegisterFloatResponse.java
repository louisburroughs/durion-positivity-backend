package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.RegisterFloatChangeKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The outcome of a float command (#2511): the register's float before and after, and the journal
 * entry that moved it, by id and by number (ADR-0064).
 */
@Schema(description = "A register's change float after a go-live or Change float command")
public record RegisterFloatResponse(
        @Schema(description = "The register (pos-order's terminalId)", example = "T-1", requiredMode = REQUIRED)
        String registerId,

        @Schema(description = "The location the register belongs to", requiredMode = REQUIRED)
        UUID locationId,

        @Schema(description = "What changed the float", requiredMode = REQUIRED)
        RegisterFloatChangeKind kind,

        @Schema(description = "The float before the command", example = "200.00", requiredMode = REQUIRED)
        BigDecimal previousAmount,

        @Schema(description = "The float after the command", example = "300.00", requiredMode = REQUIRED)
        BigDecimal amount,

        @Schema(description = "The date the entry is dated on", requiredMode = REQUIRED)
        LocalDate effectiveDate,

        @Schema(description = "The journal entry the command posted", requiredMode = REQUIRED)
        UUID journalEntryId,

        @Schema(description = "That entry's number", example = "JE-202610-000042")
        String journalEntryNumber,

        @Schema(description = "True when this answers a replayed requestId with the first result")
        boolean replayed) {}
