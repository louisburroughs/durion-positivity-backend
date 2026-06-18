package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for updating contact role assignments.
 * Issue #172: Contacts: Maintain Contact Roles and Primary Flags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Response after updating contact role assignments")
public class UpdateContactRolesResponse {

    /**
     * Party ID
     */
    @Schema(
            description = "Identifier of the party that owns the contact",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private String partyId;

    /**
     * Contact ID that was updated
     */
    @Schema(
            description = "Identifier of the contact that was updated",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private String contactId;

    /**
     * Updated version (for optimistic locking conflict resolution)
     */
    @Schema(
            description = "Updated version for optimistic locking conflict resolution",
            example = "8",
            requiredMode = NOT_REQUIRED)
    private String version;

    /**
     * Update status (SUCCESS|CONFLICT)
     */
    @Schema(description = "Update status (SUCCESS|CONFLICT)", example = "SUCCESS", requiredMode = REQUIRED)
    @NotNull
    private String status;

    /**
     * Timestamp of update (ISO 8601)
     */
    @Schema(
            description = "Timestamp of update (ISO 8601)",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private String updatedAt;
}
