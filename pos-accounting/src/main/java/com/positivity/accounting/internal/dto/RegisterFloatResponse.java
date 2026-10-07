package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.RegisterFloatChangeKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The outcome of a float command (#2511, #2571): the register's float before and after, and the journal
 * entry that moved it, by id and by number (ADR-0064). A relocation of a zero float posts nothing, so both
 * are null. The amounts' currency is stated (#2577; ADR-0067 R-1).
 */
@Schema(description = "A register's change float after a go-live, Change float or relocation command")
public record RegisterFloatResponse(
        @Schema(description = "The register (pos-order's terminalId)", example = "T-1", requiredMode = REQUIRED)
        String registerId,

        @Schema(
                description = "The location the register belongs to; after a relocation, the destination",
                requiredMode = REQUIRED)
        UUID locationId,

        @Schema(description = "What changed the float", requiredMode = REQUIRED)
        RegisterFloatChangeKind kind,

        @Schema(description = "The float before the command", example = "200.00", requiredMode = REQUIRED)
        BigDecimal previousAmount,

        @Schema(description = "The float after the command", example = "300.00", requiredMode = REQUIRED)
        BigDecimal amount,

        @Schema(
                description = "The ISO 4217 code of previousAmount and amount: the currency the register's float is"
                        + " held in, the tenant's functional currency (ADR-0067)",
                example = "USD",
                requiredMode = REQUIRED)
        String currencyCode,

        @Schema(description = "The date the entry is dated on", requiredMode = REQUIRED)
        LocalDate effectiveDate,

        @Schema(
                description = "The journal entry the command posted; null for a relocation of a zero float, which"
                        + " posts nothing",
                nullable = true)
        UUID journalEntryId,

        @Schema(description = "That entry's number; null when no entry posted", example = "JE-202610-000042")
        String journalEntryNumber,

        @Schema(description = "True when this answers a replayed requestId with the first result")
        boolean replayed) {}
