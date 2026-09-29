package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The stored audit trail of a reconciliation (SPEC §4.9, G3; story S5, #2304): the {@code AccountingAuditLog}
 * rows of the reconciliation, its matches and its outstanding items, a page at a time, oldest first. Story F2's
 * trail derived from surviving rows is retired.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Stored audit trail of a reconciliation, its matches and its outstanding items")
public class ReconciliationAuditResponse {

    @Schema(description = "Reconciliation id")
    private UUID reconciliationId;

    @Schema(description = "Audit rows of this page, oldest first")
    private List<Entry> entries;

    @Schema(description = "Rows across every page", example = "14")
    private long totalElements;

    @Schema(description = "Zero-based page index", example = "0")
    private int pageNumber;

    @Schema(description = "Page size", example = "50")
    private int pageSize;

    @Schema(description = "Number of pages", example = "1")
    private int totalPages;

    /** One stored audit row. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "ReconciliationAuditEntry", description = "One stored audit row")
    public static class Entry {

        @Schema(description = "Audit row id")
        private UUID auditLogId;

        @Schema(
                description = "What changed: BANK_RECONCILIATION, RECONCILIATION_MATCH or OUTSTANDING_ITEM",
                example = "BANK_RECONCILIATION")
        private String entityType;

        @Schema(description = "The reconciliation, match or item id")
        private UUID entityId;

        @Schema(
                description = "The operation (the endpoint's event id without ACCOUNTING_)",
                example = "RECONCILIATION_APPROVE")
        private String operation;

        @Schema(description = "Who did it (ADR-0018); for an invalidation, the actor of the triggering posting")
        private String userId;

        @Schema(description = "When it happened")
        private Instant timestamp;

        @Schema(description = "The request's trace id")
        private String traceId;

        @Schema(description = "The justification or reason given, when the operation takes one")
        private String justification;

        @Schema(
                description = "The value before, typically the status or the match / item id",
                example = "status=SUBMITTED")
        private String oldValue;

        @Schema(description = "The value after", example = "status=FINALIZED;approvedBy=controller;selfApproval=false")
        private String newValue;

        /** The entry of a stored row. */
        public static Entry from(AccountingAuditLog row) {
            return Entry.builder()
                    .auditLogId(row.getAuditLogId())
                    .entityType(row.getEntityType())
                    .entityId(row.getEntityId())
                    .operation(row.getOperation())
                    .userId(row.getUserId())
                    .timestamp(row.getTimestamp())
                    .traceId(row.getTraceId())
                    .justification(row.getJustification())
                    .oldValue(row.getOldValue())
                    .newValue(row.getNewValue())
                    .build();
        }
    }
}
