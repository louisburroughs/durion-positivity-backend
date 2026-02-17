package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.ExceptionSeverity;
import com.positivity.people.internal.enums.ExceptionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@SuppressWarnings("java:S2166")
public class TimeEntryException {
    private UUID exceptionId;
    private String employeeId;
    private LocalDate workDate;
    private String exceptionCode;
    private ExceptionSeverity severity;
    private ExceptionStatus status;
    private String timeEntryId;
    private String resolutionNotes;
    private Instant detectedAt;
    private String resolvedBy;
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
