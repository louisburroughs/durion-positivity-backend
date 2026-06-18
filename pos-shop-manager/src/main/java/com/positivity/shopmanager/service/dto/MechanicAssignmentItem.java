package com.positivity.shopmanager.service.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.service.enums.MechanicRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.NonNull;

/**
 * A mechanic entry inside a CreateAssignmentRequest.
 * {@code role} is optional; when null the service defaults it to
 * {@link MechanicRole#LEAD}
 * for single-mechanic assignments.
 */
@Value
@Builder
@Schema(description = "A single mechanic entry within an assignment request")
public class MechanicAssignmentItem {
    @Schema(
            description = "Person identifier of the mechanic being assigned",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    @NonNull
    @NotBlank
    String mechanicPersonId;

    /** May be null; single-mechanic assignments default to LEAD. */
    @Schema(
            description = "Role of the mechanic; defaults to LEAD for single-mechanic assignments when null",
            example = "LEAD",
            requiredMode = NOT_REQUIRED)
    MechanicRole role;
}
