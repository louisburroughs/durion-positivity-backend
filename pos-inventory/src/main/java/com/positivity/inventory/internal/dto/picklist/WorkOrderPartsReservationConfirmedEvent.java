package com.positivity.inventory.internal.dto.picklist;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(
        description =
                "Event signalling that parts reserved for a workorder have been confirmed, triggering pick list generation")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderPartsReservationConfirmedEvent {

    @Schema(
            description = "Identifier of the workorder whose parts reservation was confirmed",
            example = "01960003-0000-7000-8000-000000000050",
            requiredMode = REQUIRED)
    private UUID workorderId;

    @Schema(
            description = "Scheduled start time of the workorder, used to prioritize generated picks",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant scheduledStartAt;

    @Schema(
            description = "Base priority applied to the generated pick list; higher values are picked sooner",
            example = "5",
            requiredMode = REQUIRED)
    private int basePriority;

    @Schema(
            description = "Confirmed reservation lines describing the SKUs and quantities reserved",
            requiredMode = REQUIRED)
    private List<ReservationLine> reservationLines;

    @Schema(description = "A single confirmed reservation line for a SKU and quantity")
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReservationLine {

        @Schema(
                description = "Identifier of the workorder line this reservation fulfills",
                example = "01960003-0000-7000-8000-000000000051",
                requiredMode = REQUIRED)
        private UUID workorderLineId;

        @Schema(
                description = "Identifier of the confirmed reservation",
                example = "01960003-0000-7000-8000-000000000052",
                requiredMode = REQUIRED)
        private UUID reservationId;

        @Schema(
                description = "Stock keeping unit code that was reserved",
                example = "SKU-10042",
                requiredMode = REQUIRED)
        private String sku;

        @Schema(description = "Quantity of the SKU reserved on this line", example = "12", requiredMode = REQUIRED)
        private int quantity;
    }
}
