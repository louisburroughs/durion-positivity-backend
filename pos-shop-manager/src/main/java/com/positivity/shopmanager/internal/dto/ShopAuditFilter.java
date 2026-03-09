package com.positivity.shopmanager.internal.dto;

import com.positivity.shopmanager.internal.enums.ShopAuditEventType;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Filter criteria for querying the shop audit trail.
 * At least one field must be non-null (enforced at the service layer).
 * Story #61 — RQ5 filter specification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopAuditFilter {

    private String workorderId;
    private String appointmentId;
    private String mechanicId;
    private String actorUserId;
    private ShopAuditEventType eventType;
    private String locationId;

    /** Inclusive start of the date-time range filter (defaults to 90 days ago). */
    private Instant fromDateTime;

    /** Inclusive end of the date-time range filter (defaults to now). */
    private Instant toDateTime;

    /**
     * Returns {@code true} when at least one filter criterion is specified.
     * If {@code false}, the service must reject the query to prevent full-table
     * scans.
     */
    public boolean hasAtLeastOneFilter() {
        return workorderId != null
                || appointmentId != null
                || mechanicId != null
                || actorUserId != null
                || eventType != null
                || locationId != null;
    }
}
