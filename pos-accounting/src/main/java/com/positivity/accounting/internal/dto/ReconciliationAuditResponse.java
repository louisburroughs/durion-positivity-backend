package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Audit trail of a reconciliation's actions (Story F2, issue #965): import, matches,
 * adjustments, and finalize, ordered by time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Audit trail of a reconciliation's actions")
public class ReconciliationAuditResponse {

    @Schema(description = "Reconciliation id")
    private UUID reconciliationId;

    @Schema(description = "Audit entries ordered by time")
    private List<Entry> entries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "One reconciliation audit entry")
    public static class Entry {

        @Schema(description = "Action", example = "ADJUSTMENT")
        private String action;

        @Schema(description = "When the action occurred")
        private Instant at;

        @Schema(description = "Who performed the action")
        private String by;

        @Schema(description = "Human-readable detail")
        private String detail;
    }
}
