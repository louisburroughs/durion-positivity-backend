package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.ExceptionSeverity;
import com.positivity.people.internal.enums.ExceptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "Time entry exception representing a discrepancy or issue with time tracking")
@SuppressWarnings("java:S2166")
public class TimeEntryException {

    @Schema(
            description = "Unique identifier for the exception",
            example = "123e4567-e89b-12d3-a456-426614174000",
            accessMode = Schema.AccessMode.READ_ONLY)
    private UUID exceptionId;

    @Schema(
            description = "Employee identifier associated with the exception",
            example = "EMP-001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String employeeId;

    @Schema(
            description = "Work date when the exception occurred",
            example = "2026-02-17",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate workDate;

    @Schema(
            description = "Exception code identifying the type of exception",
            example = "MISSED_CLOCK_OUT",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String exceptionCode;

    @Schema(
            description = "Severity level of the exception",
            example = "HIGH",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private ExceptionSeverity severity;

    @Schema(
            description = "Current status of the exception",
            example = "PENDING",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private ExceptionStatus status;

    @Schema(description = "Associated time entry identifier", example = "te-12345")
    private String timeEntryId;

    @Schema(
            description = "Notes describing resolution or actions taken",
            example = "Manager manually adjusted time entry")
    private String resolutionNotes;

    @Schema(description = "Timestamp when the exception was detected", example = "2026-02-17T08:30:00Z")
    private Instant detectedAt;

    @Schema(description = "User who resolved the exception", example = "manager-123")
    private String resolvedBy;

    @Schema(description = "Timestamp when the exception was resolved", example = "2026-02-17T09:15:00Z")
    private Instant resolvedAt;

    public UUID getExceptionId() {
        return exceptionId;
    }

    public void setExceptionId(UUID exceptionId) {
        this.exceptionId = exceptionId;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public void setWorkDate(LocalDate workDate) {
        this.workDate = workDate;
    }

    public String getExceptionCode() {
        return exceptionCode;
    }

    public void setExceptionCode(String exceptionCode) {
        this.exceptionCode = exceptionCode;
    }

    public ExceptionSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(ExceptionSeverity severity) {
        this.severity = severity;
    }

    public ExceptionStatus getStatus() {
        return status;
    }

    public void setStatus(ExceptionStatus status) {
        this.status = status;
    }

    public String getTimeEntryId() {
        return timeEntryId;
    }

    public void setTimeEntryId(String timeEntryId) {
        this.timeEntryId = timeEntryId;
    }

    public String getResolutionNotes() {
        return resolutionNotes;
    }

    public void setResolutionNotes(String resolutionNotes) {
        this.resolutionNotes = resolutionNotes;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(Instant detectedAt) {
        this.detectedAt = detectedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
