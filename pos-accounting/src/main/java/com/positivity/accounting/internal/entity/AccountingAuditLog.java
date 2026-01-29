package com.positivity.accounting.internal.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Accounting Audit Log - comprehensive audit trail for high-risk operations.
 * 
 * Required for: post JE, reverse JE, approve/reject vendor bills,
 * publish/archive rules, deactivate accounts.
 * 
 * Retention: 7 years (financial regulatory compliance).
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Audit Trail</a>
 */
@Entity
@Table(name = "accounting_audit_log", indexes = {
        @Index(name = "idx_audit_log_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_log_operation", columnList = "operation"),
        @Index(name = "idx_audit_log_user", columnList = "user_id"),
        @Index(name = "idx_audit_log_timestamp", columnList = "timestamp"),
        @Index(name = "idx_audit_log_trace", columnList = "trace_id")
})
public class AccountingAuditLog {

    @Id
    @Column(name = "audit_log_id", length = 50, nullable = false)
    private String auditLogId;

    @Column(name = "entity_type", length = 50, nullable = false)
    private String entityType;

    @Column(name = "entity_id", length = 50, nullable = false)
    private String entityId;

    @Column(name = "operation", length = 50, nullable = false)
    private String operation;

    @Column(name = "user_id", length = 50, nullable = false)
    private String userId;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "justification", length = 2000)
    private String justification;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    // Constructors
    public AccountingAuditLog() {
    }

    // Getters and Setters
    public String getAuditLogId() {
        return auditLogId;
    }

    public void setAuditLogId(String auditLogId) {
        this.auditLogId = auditLogId;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getJustification() {
        return justification;
    }

    public void setJustification(String justification) {
        this.justification = justification;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    @PrePersist
    protected void onCreate() {
        this.timestamp = Instant.now();
    }
}
