package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Request DTO for starting a labor session.
 * 
 * <p>
 * Implements CAP-005 Story #159 - Record Labor Performed
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to start a labor session on a workorder service")
public class StartLaborRequest {

    @NotNull(message = "Technician ID is required")
    @Schema(description = "ID of the technician performing the work", example = "550e8400-e29b-41d4-a716-446655440050")
    @JsonProperty("technicianId")
    private UUID technicianId;

    @Nullable
    @Schema(description = "Optional notes about the labor session", example = "Beginning brake pad replacement")
    @JsonProperty("notes")
    private String notes;
}
