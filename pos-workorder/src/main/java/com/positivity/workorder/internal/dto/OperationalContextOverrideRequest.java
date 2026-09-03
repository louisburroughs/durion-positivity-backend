package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Manager override payload for workorder operational context")
public class OperationalContextOverrideRequest {
    @NotNull
    @Schema(
            description = "Location identifier to set on the workorder",
            example = "550e8400-e29b-41d4-a716-446655440300",
            requiredMode = REQUIRED)
    private UUID locationId;

    /**
     * Which resource aggregate the first entry of {@code assignedResources} names (#1656).
     *
     * <p>An override that moves a workorder from a bay to a mobile unit has to move the type with
     * the id — writing the id alone would leave the workorder pointing at a van while still typed
     * as a bay, which puts it in the dispatch board's {@code bays[]} panel and simultaneously
     * advertises the van as free. The same full-replace rule the assignment-event path follows
     * applies here, and an absent value resolves through
     * {@link com.positivity.workorder.internal.enums.ResourceType#orDefault} to {@code BAY}.
     *
     * <p>Unlike the Kafka assignment path, this field binds <em>strictly</em>: a token that is not
     * {@code BAY} or {@code MOBILE_UNIT} is rejected as a 400 {@code ApiError} (ADR-0017) rather
     * than being downgraded to "absent". A synchronous caller can be told it sent garbage, and the
     * alternative was worse — {@code "MOBILE-UNIT"} used to return 200 having written the van's id
     * under the {@code BAY} type, which is precisely the half-applied state the surrounding code
     * exists to prevent (#1656).
     */
    @Schema(
            description = "Kind of resource the first assignedResources entry points at. Optional: an "
                    + "absent value is interpreted as BAY.",
            example = "MOBILE_UNIT",
            requiredMode = NOT_REQUIRED)
    private ResourceType resourceType;

    @Schema(
            description = "Assigned mechanic identifiers",
            example = "[\"550e8400-e29b-41d4-a716-446655440120\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> assignedMechanics;

    @Schema(
            description = "Assigned resource identifiers",
            example = "[\"550e8400-e29b-41d4-a716-446655440301\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> assignedResources;

    @Schema(
            description = "Optional execution constraints",
            example = "[\"ALIGNMENT_RACK_REQUIRED\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> constraints;
}
