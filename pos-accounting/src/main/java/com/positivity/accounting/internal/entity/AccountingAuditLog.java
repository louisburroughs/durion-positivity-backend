package com.positivity.accounting.internal.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

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
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(name = "accounting_audit_log", indexes = {
        @Index(name = "idx_audit_log_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_log_operation", columnList = "operation"),
        @Index(name = "idx_audit_log_user", columnList = "user_id"),
        @Index(name = "idx_audit_log_timestamp", columnList = "timestamp"),
        @Index(name = "idx_audit_log_trace", columnList = "trace_id")
})
public class AccountingAuditLog {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "audit_log_id", nullable = false)
    private UUID auditLogId;

    @Column(name = "entity_type", length = 50, nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

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

    @PrePersist
    protected void onCreate() {
        this.timestamp = Instant.now();
    }
}
