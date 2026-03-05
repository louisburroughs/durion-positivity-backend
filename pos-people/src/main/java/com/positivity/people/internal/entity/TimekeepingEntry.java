package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.ApprovalStatus;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.GeneratedValue;
import com.positivity.shared.id.UUIDv7Id;
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "timekeeping_entry", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "source_system", "source_session_id" })
})
public class TimekeepingEntry {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "timekeeping_entry_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID timekeepingEntryId;

    @Column(name = "tenant_id", columnDefinition = "UUID", nullable = false)
    private UUID tenantId;

    @Column(name = "source_system", nullable = false, length = 50)
    private String sourceSystem = "shopmgr";

    @Column(name = "source_session_id", columnDefinition = "UUID", nullable = false)
    private UUID sourceSessionId;

    @Column(name = "original_source_session_id", nullable = true)
    private UUID originalSourceSessionId;

    @Column(name = "correction_id", nullable = true)
    private UUID correctionId;

    @Column(name = "correction_reason", nullable = true, columnDefinition = "TEXT")
    private String correctionReason;

    @Column(name = "employee_id", columnDefinition = "UUID", nullable = false)
    private UUID employeeId;

    @Column(name = "session_start_time", nullable = false)
    private Instant sessionStartTime;

    @Column(name = "session_end_time", nullable = false)
    private Instant sessionEndTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 50)
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING_APPROVAL;

    @Column(name = "associated_work_order_id", columnDefinition = "UUID")
    private UUID associatedWorkOrderId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    public UUID getTimekeepingEntryId() {
        return timekeepingEntryId;
    }

    public void setTimekeepingEntryId(UUID timekeepingEntryId) {
        this.timekeepingEntryId = timekeepingEntryId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public UUID getSourceSessionId() {
        return sourceSessionId;
    }

    public void setSourceSessionId(UUID sourceSessionId) {
        this.sourceSessionId = sourceSessionId;
    }

    public UUID getOriginalSourceSessionId() {
        return originalSourceSessionId;
    }

    public void setOriginalSourceSessionId(UUID originalSourceSessionId) {
        this.originalSourceSessionId = originalSourceSessionId;
    }

    public UUID getCorrectionId() {
        return correctionId;
    }

    public void setCorrectionId(UUID correctionId) {
        this.correctionId = correctionId;
    }

    public String getCorrectionReason() {
        return correctionReason;
    }

    public void setCorrectionReason(String correctionReason) {
        this.correctionReason = correctionReason;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(UUID employeeId) {
        this.employeeId = employeeId;
    }

    public Instant getSessionStartTime() {
        return sessionStartTime;
    }

    public void setSessionStartTime(Instant sessionStartTime) {
        this.sessionStartTime = sessionStartTime;
    }

    public Instant getSessionEndTime() {
        return sessionEndTime;
    }

    public void setSessionEndTime(Instant sessionEndTime) {
        this.sessionEndTime = sessionEndTime;
    }

    public ApprovalStatus getApprovalStatus() {
        return approvalStatus;
    }

    public void setApprovalStatus(ApprovalStatus approvalStatus) {
        this.approvalStatus = approvalStatus;
    }

    public UUID getAssociatedWorkOrderId() {
        return associatedWorkOrderId;
    }

    public void setAssociatedWorkOrderId(UUID associatedWorkOrderId) {
        this.associatedWorkOrderId = associatedWorkOrderId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
}
}