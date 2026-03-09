package com.positivity.shopmanager.internal.dto;

import com.positivity.shopmanager.internal.enums.ShopAuditEventType;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only response DTO for a shop audit trail entry.
 * Story #61 — Audit Trail for Schedule and Assignment Changes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopAuditEntryResponse {

    private UUID id;
    private ShopAuditEventType eventType;
    private String workorderId;
    private String appointmentId;
    private String mechanicId;
    private String locationId;
    private String actorUserId;
    private String changeSummaryText;
    private String changePatch;
    private String reasonCode;
    private int retentionYears;
    private Instant recordedAt;
}
