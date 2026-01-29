package com.positivity.people.internal.entity;

import jakarta.persistence.*;
import java.time.Instant;
import com.positivity.people.internal.model.ExceptionStatus;
import com.positivity.people.internal.model.ExceptionSeverity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "time_entry_exception")
public class TimeEntryException {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "exception_id", updatable = false, nullable = false)
    private UUID exceptionId;

    @Column(name = "employee_id", nullable = false)
    private String employeeId;

    @Column(name = "work_date")
    private LocalDate workDate;

    @Column(name = "exception_code", length = 200)
    private String exceptionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 50)
    private ExceptionSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50)
    private ExceptionStatus status;

    @Column(name = "time_entry_id")
    private String timeEntryId;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "detected_at")
    private Instant detectedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    public void prePersist() {
        if (detectedAt == null)
            detectedAt = Instant.now();
    }

    // Getters and setters
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
