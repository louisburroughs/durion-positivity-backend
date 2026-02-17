package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;

@Entity
@Table(name = "time_entry")
public class TimeEntry {

    @Id
    @Column(name = "time_entry_id", columnDefinition = "UUID", nullable = false)
    private UUID timeEntryId;

    @PrePersist
    public void generateId() {
        if (timeEntryId == null) {
            timeEntryId = UUIDv7Generator.generate();
        }
    }

    @Column(name = "person_id")
    private String personId;

    @Column(name = "timesheet_id")
    private String timesheetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private TimeEntryStatus status;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_by")
    private String rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    public UUID getTimeEntryId() {
        return timeEntryId;
    }

    public void setTimeEntryId(UUID timeEntryId) {
        this.timeEntryId = timeEntryId;
    }

    public String getPersonId() {
        return personId;
    }

    public void setPersonId(String personId) {
        this.personId = personId;
    }

    public String getTimesheetId() {
        return timesheetId;
    }

    public void setTimesheetId(String timesheetId) {
        this.timesheetId = timesheetId;
    }

    public TimeEntryStatus getStatus() {
        return status;
    }

    public void setStatus(TimeEntryStatus status) {
        this.status = status;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getRejectedBy() {
        return rejectedBy;
    }

    public void setRejectedBy(String rejectedBy) {
        this.rejectedBy = rejectedBy;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(Instant rejectedAt) {
        this.rejectedAt = rejectedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }
}
