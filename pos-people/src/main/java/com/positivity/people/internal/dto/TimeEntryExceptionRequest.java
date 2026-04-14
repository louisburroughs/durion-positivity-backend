package com.positivity.people.internal.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "Request to create a time entry exception")
public class TimeEntryExceptionRequest {

    @Schema(description = "Employee identifier associated with the exception", example = "EMP-001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String employeeId;

    @JsonAlias("issueCode")
    @Schema(description = "Exception code identifying the type of exception", example = "MISSED_CLOCK_OUT",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String exceptionCode;

    @Schema(description = "Severity level of the exception", example = "HIGH",
            allowableValues = { "LOW", "MEDIUM", "HIGH", "CRITICAL" })
    private String severity;

    @Schema(description = "Time entry identifier associated with the exception", example = "te-12345")
    private String timeEntryId;

    @Schema(description = "Notes describing the exception or initial resolution steps",
            example = "Employee forgot to clock out at end of shift")
    private String resolutionNotes;

    @Schema(description = "Timestamp when the exception was detected", example = "2026-02-17T08:30:00-05:00")
    private OffsetDateTime detectedAt;

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public String getExceptionCode() {
        return exceptionCode;
    }

    public void setExceptionCode(String exceptionCode) {
        this.exceptionCode = exceptionCode;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
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

    public OffsetDateTime getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(OffsetDateTime detectedAt) {
        this.detectedAt = detectedAt;
    }

}
