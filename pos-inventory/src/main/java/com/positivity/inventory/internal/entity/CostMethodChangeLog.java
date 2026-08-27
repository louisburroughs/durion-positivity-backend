package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.CostMethodChangeType;
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
 * ADR-0048 "method changes recorded: who/when/from/to"). Switching a method
 * going forward is J1; restating opening values (the revaluation cut-over) is
 * J4 — this log is the who/when/from/to audit that governs it.
 *
 * <p>{@link CostMethodChangeType} says which kind of change a row records
 * (#1535). The admin upsert writes {@code METHOD_SET} when a scope's method is
 * created or changed, and {@code REACTIVATED} when a previously deactivated row
 * is brought back at its existing method; deactivating a row writes
 * {@code DEACTIVATED}. Before #1535 only the first of those existed, so
 * retiring a scope override — the decisive step of the SKU_CATEGORY cut-over —
 * left no audit row at all, and a deactivate/reactivate round trip was
 * invisible because the method had not changed on the way back.
 *
 * <p>{@code createdAt} is the change instant (the "when"). {@code fromMethod}
 * is null when the scope had no prior configured method. {@code toMethod} is
 * null only on a {@code DEACTIVATED} row: a retired override resolves to
 * nothing rather than to something else.
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

    /** Newly configured method at this scope; null on a {@code DEACTIVATED} row. */
    @Enumerated(EnumType.STRING)
    @Column(name = "to_method", length = 32)
    private CostingMethod toMethod;

    /** Which kind of change this row records (#1535). */
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 32)
    private CostMethodChangeType changeType;

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
