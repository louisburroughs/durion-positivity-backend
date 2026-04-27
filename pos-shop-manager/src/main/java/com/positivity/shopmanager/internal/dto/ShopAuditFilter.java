package com.positivity.shopmanager.internal.dto;

import com.positivity.shopmanager.internal.enums.ShopAuditEventType;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Filter criteria for querying the shop audit trail. At least one field must be non-null.")
public class ShopAuditFilter {

    @Schema(description = "Workorder UUID to filter by - one word per workspace naming policy")
    private String workorderId;

    @Schema(description = "Appointment UUID to filter by")
    private String appointmentId;

    @Schema(description = "Mechanic user ID to filter by")
    private String mechanicId;

    @Schema(description = "Actor user ID to filter by")
    private String actorUserId;

    @Schema(description = "Specific audit event type to filter by")
    private ShopAuditEventType eventType;

    @Schema(description = "Location ID to filter by")
    private String locationId;

    /** Inclusive start of the date-time range filter (defaults to 90 days ago). */
    @Schema(description = "Inclusive start timestamp for date-time range filter", example = "2026-01-01T00:00:00Z")
    private Instant fromDateTime;

    /** Inclusive end of the date-time range filter (defaults to now). */
    @Schema(description = "Inclusive end timestamp for date-time range filter", example = "2026-12-31T23:59:59Z")
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
