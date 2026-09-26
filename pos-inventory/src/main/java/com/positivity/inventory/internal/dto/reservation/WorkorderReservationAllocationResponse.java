package com.positivity.inventory.internal.dto.reservation;

import com.positivity.inventory.internal.enums.AllocationState;
import com.positivity.inventory.internal.enums.AllocationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/** One allocation under a {@link WorkorderReservationResponse} (issue #2233). */
@Data
@Builder
@Schema(description = "One allocation under a reservation, narrowed to the caller's location-scope reach")
public class WorkorderReservationAllocationResponse {

    @Schema(description = "Identifier of the allocation")
    private UUID allocationId;

    @Schema(
            description =
                    "Storage location or site the allocation is pinned to; null for an unlocated SOFT" + " allocation")
    private UUID locationId;

    @Schema(description = "Quantity allocated by this allocation")
    private BigDecimal allocatedQuantity;

    @Schema(description = "SOFT or HARD")
    private AllocationState allocationState;

    @Schema(description = "ALLOCATED, PICKED, or RELEASED")
    private AllocationStatus status;
}
