package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.RegisterSessionStatus;
import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Read-only replica of one pos-order register session (#2571, #2573; ADR-0044 R3). Written only by the {@code
 * order.events.v1} consumer from the {@code order.session.opened} and {@code order.session.closed} facts; the
 * register float relocation reads it to refuse moving a register whose latest-opened session is open.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@Table(name = "ext_order_register_session")
public class ExtOrderRegisterSession extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @AssignedIdentifier("pos-order's sessionId, the facts' aggregateId")
    @Column(name = "session_id", nullable = false, columnDefinition = "UUID")
    private UUID sessionId;

    /** pos-order's terminalId: the register (AW31). */
    @Column(name = "terminal_id", length = 100, nullable = false)
    private String terminalId;

    @Column(name = "location_id")
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private RegisterSessionStatus status;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
