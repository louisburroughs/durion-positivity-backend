package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for submitting an accounting event for sync ingestion.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Accounting Event Request</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for submitting an accounting event for ingestion")
public class AccountingEventSubmitRequest {

    @Schema(
            description = "Optional event UUID (idempotency key). If omitted, service computes idempotency hash.",
            example = "01936e5c-1234-7a3d-8b6e-123456789012")
    private UUID eventId;

    @NotBlank(message = "eventType is required")
    @Size(max = 100, message = "eventType must not exceed 100 characters")
    @Schema(
            description = "Event type identifier",
            example = "INVOICE_RECEIVED",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String eventType;

    /**
     * @deprecated Vestigial multi-tenancy scope key. ADR-0023 retired multi-tenancy and no
     *     organization directory exists on the platform, so nothing validates, resolves or
     *     displays this value — it is stored verbatim and read by nothing (issue #1894). Accepted
     *     for the producers that still send one; new callers should omit it. Slated for removal
     *     with the {@code accounting_event.organization_id} column.
     */
    @Deprecated
    @Schema(
            description = "Deprecated and ignored. Vestigial multi-tenancy scope key retained only for "
                    + "producers that still send one: nothing resolves or displays it. Omit it.",
            example = "00000000-0000-4000-a000-000000000010",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            deprecated = true,
            nullable = true)
    private UUID organizationId;

    @Size(max = 100, message = "sourceSystem must not exceed 100 characters")
    @Schema(description = "Source system name. If omitted, defaults to POS_ACCOUNTING_API.", example = "POS_ORDER")
    private String sourceSystem;

    @Schema(
            description = "Event transaction timestamp. If omitted, defaults to current time.",
            example = "2026-01-15T10:30:00")
    private LocalDateTime transactionDate;

    @NotNull(message = "payload is required")
    @Schema(description = "Event-specific payload content", requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<String, Object> payload;

    /**
     * Convert this request to a Map for service processing.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("eventId", eventId);
        map.put("eventType", eventType);
        map.put("organizationId", organizationId);
        map.put("sourceSystem", sourceSystem);
        map.put(
                "transactionDate",
                transactionDate != null ? transactionDate.toLocalDate().atStartOfDay() : null);
        map.put("payload", payload);
        return map;
    }
}
