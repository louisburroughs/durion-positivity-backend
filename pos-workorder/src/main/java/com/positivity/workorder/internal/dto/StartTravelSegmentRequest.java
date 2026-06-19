package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.OnBehalfReasonCode;
import com.positivity.workorder.internal.enums.TravelSegmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request body for starting a travel segment")
public class StartTravelSegmentRequest {

    @NotNull
    @Schema(
            description = "Identifier of the mobile work assignment the travel segment belongs to",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID mobileWorkAssignmentId;

    @NotNull
    @Schema(
            description = "Identifier of the technician performing the travel",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = REQUIRED)
    private UUID technicianId;

    @NotNull
    @Schema(
            description = "Direction or purpose of this travel leg",
            example = "DEPART_SHOP",
            requiredMode = REQUIRED)
    private TravelSegmentType segmentType;

    @Schema(
            description = "Identifier of the origin location for the travel leg",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID fromLocationId;

    @Schema(
            description = "Identifier of the destination location for the travel leg",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID toLocationId;

    @Schema(
            description = "Identifier of the workorder associated with the travel",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID workOrderId;

    @Schema(
            description = "Identifier of the person on whose behalf this segment is entered, if applicable",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private UUID actedForPersonId;

    @Schema(
            description = "Reason code when the segment is entered on behalf of another person",
            example = "TECHNICIAN_UNAVAILABLE",
            requiredMode = NOT_REQUIRED)
    private OnBehalfReasonCode onBehalfReasonCode;
}
