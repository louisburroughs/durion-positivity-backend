package com.positivity.warranty.internal.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read-only client for pos-workorder. Warranty never writes to pos-workorder
 * (PRD §9.4).
 *
 * <p>Reads degrade gracefully: empty list / {@link Optional#empty()} on 404 or
 * transport failure.
 */
public interface WorkorderClient {

    /**
     * Search workorders filtered by customer and/or vehicle
     * ({@code GET /v1/workorders/search?customerId=&vehicleId=}). Either filter
     * may be null; passing both narrows the result.
     */
    @NonNull
    List<WorkorderSummary> searchWorkorders(@Nullable UUID customerId, @Nullable UUID vehicleId);

    /**
     * Fetch full workorder detail with service and part lines
     * ({@code GET /v1/workorders/{workorderId}/detail}).
     */
    @NonNull
    Optional<WorkorderDetail> getWorkorderDetail(@NonNull UUID workorderId);

    /** Condensed row from the workorder search API. */
    record WorkorderSummary(
            UUID workorderId,
            String workorderNumber,
            String status,
            UUID customerId,
            UUID vehicleId,
            String vin,
            Instant createdAt) {}

    /** Workorder detail slice warranty claims need. */
    record WorkorderDetail(
            UUID workorderId,
            String workorderNumber,
            UUID customerId,
            UUID vehicleId,
            List<ServiceLine> services,
            List<PartLine> parts) {}

    /**
     * Labor/service line.
     *
     * @param laborHours nullable — authorized hours for the line
     * @param laborRate nullable — hourly rate
     */
    record ServiceLine(
            UUID id, String description, BigDecimal laborHours, BigDecimal laborRate, BigDecimal lineTotal) {}

    /** Part line, including photo evidence captured on the workorder. */
    record PartLine(
            UUID id,
            UUID productEntityId,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            String photoEvidenceUrl) {}
}
