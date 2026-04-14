package com.positivity.people.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.people.internal.enums.AssignmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Staffing assignment response")
public class StaffingAssignmentResponse {

    @Schema(
            description = "Assignment identifier",
            example = "33333333-3333-3333-3333-333333333333",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID assignmentId;

    @Schema(
            description = "Person identifier",
            example = "123e4567-e89b-12d3-a456-426614174000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(
            description = "Location identifier",
            example = "22222222-2222-2222-2222-222222222222",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID locationId;

    @Schema(description = "Assignment role", example = "TECHNICIAN", requiredMode = Schema.RequiredMode.REQUIRED)
    private String role;

    @JsonProperty("isPrimary")
    @Schema(
            description = "Whether this assignment is primary",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean primary;

    @Schema(description = "Assignment lifecycle status", requiredMode = Schema.RequiredMode.REQUIRED)
    private AssignmentStatus status;

    @Schema(
            description = "Assignment effective start date",
            example = "2026-02-16",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate effectiveFrom;

    @Schema(description = "Assignment effective end date", example = "2026-12-31")
    private LocalDate effectiveTo;

    @Schema(description = "Creation timestamp in UTC", example = "2026-02-16T14:30:00Z")
    private Instant createdAt;

    @Schema(description = "Last update timestamp in UTC", example = "2026-02-18T09:15:00Z")
    private Instant updatedAt;

    @Schema(description = "Username that created the assignment", example = "manager.user")
    private String createdBy;

    public StaffingAssignmentResponse() {}

    public StaffingAssignmentResponse(
            UUID assignmentId,
            UUID personId,
            UUID locationId,
            String role,
            boolean primary,
            AssignmentStatus status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant createdAt,
            Instant updatedAt,
            String createdBy) {
        this.assignmentId = assignmentId;
        this.personId = personId;
        this.locationId = locationId;
        this.role = role;
        this.primary = primary;
        this.status = status;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.createdBy = createdBy;
    }

    public UUID getAssignmentId() {
        return assignmentId;
    }

    public void setAssignmentId(UUID assignmentId) {
        this.assignmentId = assignmentId;
    }

    public UUID getPersonId() {
        return personId;
    }

    public void setPersonId(UUID personId) {
        this.personId = personId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    @JsonProperty("isPrimary")
    public boolean isPrimary() {
        return primary;
    }

    @JsonProperty("isPrimary")
    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public AssignmentStatus getStatus() {
        return status;
    }

    public void setStatus(AssignmentStatus status) {
        this.status = status;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
