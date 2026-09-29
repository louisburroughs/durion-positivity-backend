package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cancel or supersede a reconciliation (SPEC §4.9; story S5, #2304). {@code requestId} is read by supersede
 * only, which creates a reconciliation and replays like every create command (§6.3).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Cancel or supersede a reconciliation, with a justification")
public class ReconciliationJustificationRequest {

    @Size(max = 1000, message = "justification must not exceed 1000 characters")
    @Schema(
            description = "Why (at least 10 characters)",
            example = "The September fee reversal invalidated the approval; re-reconcile the window",
            requiredMode = REQUIRED)
    private String justification;

    @Schema(
            description = "Supersede only: caller-generated UUIDv7; a replay returns the successor it created",
            example = "019a0000-0000-7000-8000-000000000009")
    private UUID requestId;

    @Schema(
            description = "The reconciliation version the caller read; a stale one answers 409 OPTIMISTIC_LOCK",
            example = "5")
    private Long version;
}
