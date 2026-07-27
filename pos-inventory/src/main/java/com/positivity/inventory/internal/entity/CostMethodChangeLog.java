package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Append-only history of costing-method changes (odoo-parity J1, issue #1048;
 * ADR-0048 "method changes recorded: who/when/from/to"). One row is written by
 * the admin upsert whenever a scope's method is created or changed. Switching a
 * method going forward is J1; restating opening values (the revaluation
 * cut-over) is J4 — this log is the who/when/from/to audit that governs it.
 *
 * <p>{@code createdAt} is the change instant (the "when"). {@code fromMethod}
 * is null when the scope had no prior configured method.
 */
@Entity
@Table(
        name = "cost_method_change_log",
        indexes = @Index(name = "idx_cost_method_change_log_scope", columnList = "scope_type, scope_value"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class CostMethodChangeLog {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "change_id", updatable = false, nullable = false)
    private UUID changeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 32)
    private CostingScopeType scopeType;

    /** Stock item id, category string, or null for DEFAULT scope. */
    @Column(name = "scope_value", length = 255)
    private String scopeValue;

    /** Previously configured method at this scope, or null on first configuration. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_method", length = 32)
    private CostingMethod fromMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_method", nullable = false, length = 32)
    private CostingMethod toMethod;

    @Column(name = "changed_by", nullable = false, length = 255)
    private String changedBy;

    /** The change instant (the "when" of the who/when/from/to record). */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
