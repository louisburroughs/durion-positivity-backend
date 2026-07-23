package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.SourcingScopeType;
import com.positivity.inventory.internal.enums.SourcingStrategy;
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
 * One sourcing-strategy configuration row (odoo-parity H1, issue #1037):
 * which {@link SourcingStrategy} applies at one scope. Resolution precedence
 * is SKU_CATEGORY → SITE → DEFAULT → platform default FIFO, evaluated by
 * {@code SourcingStrategyServiceImpl}.
 *
 * <p>{@code scopeValue} semantics per {@link SourcingScopeType}: the category
 * string for SKU_CATEGORY, the site UUID rendered as text for SITE, and null
 * for DEFAULT. At most one active row exists per (scopeType, scopeValue) —
 * enforced by the V17 unique index; admin upsert reuses the existing row.
 * Rows are deactivated, never hard-deleted.
 */
@Entity
@Table(
        name = "sourcing_strategy_config",
        indexes = @Index(name = "idx_sourcing_strategy_config_scope", columnList = "scope_type, scope_value"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class SourcingStrategyConfig {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "config_id", updatable = false, nullable = false)
    private UUID configId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 32)
    private SourcingScopeType scopeType;

    /** Category string, site UUID as text, or null for DEFAULT scope. */
    @Column(name = "scope_value", length = 255)
    private String scopeValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, length = 32)
    private SourcingStrategy strategy;

    @Column(name = "active", nullable = false)
    private boolean active;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
