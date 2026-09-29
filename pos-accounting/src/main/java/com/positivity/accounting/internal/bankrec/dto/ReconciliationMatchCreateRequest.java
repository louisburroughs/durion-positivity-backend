package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A human match (SPEC §3.4, §4.6; story S4, #2303): bank transactions and posted ledger lines that describe
 * the same cash movement. One side may have several members (1:N, N:1), never both.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Match bank transactions to posted ledger lines (1:1, 1:N or N:1)")
public class ReconciliationMatchCreateRequest {

    @NotEmpty(message = "bankTransactionIds is required")
    @Schema(description = "Bank transactions of the match", requiredMode = REQUIRED)
    private List<UUID> bankTransactionIds;

    @NotEmpty(message = "glLineIds is required")
    @Schema(description = "Posted journal-entry lines on the reconciled account", requiredMode = REQUIRED)
    private List<UUID> glLineIds;

    @Size(max = 1000, message = "justification must not exceed 1000 characters")
    @Schema(
            description = "Required (at least 10 characters) when the match is not 1:1, uses the ±0.01 tolerance,"
                    + " spans dates beyond the window or includes a former possible duplicate",
            example = "Batch deposit of two receipts")
    private String justification;

    @NotNull(message = "requestId is required")
    @Schema(
            description = "Caller-generated UUIDv7; a replay with the same members returns the match",
            requiredMode = REQUIRED)
    private UUID requestId;
}
