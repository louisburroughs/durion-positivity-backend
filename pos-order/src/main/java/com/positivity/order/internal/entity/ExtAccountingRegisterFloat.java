package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * pos-order's copy of one register's configured float (CAP:550 S16, #2512; ADR-0044 R3, AW16),
 * written only by {@code accounting.float.changed} (S15) and guarded by the fact's {@code
 * aggregateVersion}. Keyed by the fact's aggregate, the accounting float row; {@code registerId} is
 * pos-order's {@code terminalId}. A session opens with this amount, and a float movement must match the
 * difference between it and the drawer's float. The amount can be negative after an accounting reversal.
 */
@Entity
@Table(name = "ext_accounting_register_float")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtAccountingRegisterFloat extends TenantScopedEntity {

    @Id
    @Column(name = "register_float_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID registerFloatId;

    @Column(name = "register_id", nullable = false)
    private String registerId;

    @Column(name = "location_id", nullable = false, columnDefinition = "UUID")
    private UUID locationId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    /** ArchUnit UUIDv7 rule hook: the id is a UUIDv7 issued by pos-accounting. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
