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
 * One costing-method configuration row (odoo-parity J1, issue #1048;
 * ADR-0048): which {@link CostingMethod} applies at one scope. Resolution
 * precedence is SKU → SKU_CATEGORY → DEFAULT → deployment default
 * {@code pos.inventory.valuation.default-method}, evaluated by
 * {@code CostingMethodResolver}.
 *
 * <p>Mirrors {@link SourcingStrategyConfig} (odoo-parity H1). {@code scopeValue}
 * carries the stock item id for SKU, the category string for SKU_CATEGORY, and
 * null for DEFAULT. At most one active row exists per (scopeType, scopeValue) —
 * enforced by the V26 unique index; admin upsert reuses the existing row. Rows
 * are deactivated, never hard-deleted.
 */
@Entity
@Table(
        name = "sku_cost_method_config",
        indexes = @Index(name = "idx_sku_cost_method_config_scope", columnList = "scope_type, scope_value"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class CostingMethodConfig {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "config_id", updatable = false, nullable = false)
    private UUID configId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 32)
    private CostingScopeType scopeType;

    /** Stock item id, category string, or null for DEFAULT scope. */
    @Column(name = "scope_value", length = 255)
    private String scopeValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 32)
    private CostingMethod method;

    @Column(name = "active", nullable = false)
    private boolean active;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
