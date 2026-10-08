package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * What one session contributed to one deposit when the deposit was recorded (CAP:550 S18, #2514). Never changed: a
 * reversed deposit keeps its sessions as history, and its location ids gate who may see or reverse it (ADR-0061).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Immutable
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "deposit_session",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_deposit_session",
                    columnNames = {"tenant_id", "deposit_id", "session_id"})
        })
public class DepositSession extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "deposit_session_id", nullable = false, columnDefinition = "UUID")
    private UUID depositSessionId;

    @Column(name = "deposit_id", nullable = false, updatable = false)
    private UUID depositId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "terminal_id", length = 100, nullable = false, updatable = false)
    private String terminalId;

    @Column(name = "location_id", updatable = false)
    private UUID locationId;

    @Column(name = "closed_at", nullable = false, updatable = false)
    private Instant closedAt;

    @Column(name = "deposit_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal depositAmount;

    @Column(name = "expected_cash", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal expectedCash;

    @Column(name = "clearing_net", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal clearingNet;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
