package com.positivity.inventory.internal.dto.reservation;

import com.positivity.inventory.internal.enums.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * One reservation of a workorder's part lines, with its allocations (issue #2233): the
 * allocation-id lookup the shortage page needs, since it holds only a workorderId (and the
 * frontend's {@code allocationLineId}, which names the allocation id) but
 * {@code listShortageOptions}/{@code resolveShortage} need an {@code allocationId}.
 */
@Data
@Builder
@Schema(description = "A reservation for one of a workorder's part lines, with its allocations")
public class WorkorderReservationResponse {

    @Schema(description = "Identifier of the reservation")
    private UUID reservationId;

    @Schema(description = "Workorder line this reservation fulfils")
    private UUID workorderLineId;

    @Schema(description = "SKU / stock-item identifier reserved")
    private String sku;

    @Schema(description = "Quantity required by the reservation")
    private BigDecimal requiredQuantity;

    @Schema(description = "Quantity currently allocated against the reservation")
    private BigDecimal allocatedQuantity;

    @Schema(
            description = "Quantity still short: requiredQuantity minus allocatedQuantity, floored at zero"
                    + " (matches how listShortageOptions/resolveShortage derive it from the allocation)")
    private BigDecimal shortQuantity;

    @Schema(description = "Reservation lifecycle status")
    private ReservationStatus status;

    @Schema(
            description = "The reservation's allocations, narrowed to the caller's location-scope reach"
                    + " (ADR-0061); a reservation with no in-reach allocation is still listed with its quantities")
    private List<WorkorderReservationAllocationResponse> allocations;
}
