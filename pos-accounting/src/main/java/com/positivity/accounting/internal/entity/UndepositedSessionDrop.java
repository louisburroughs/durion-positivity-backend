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
 * One {@code BANK_DROP} movement of an undeposited session (CAP:550 S18, #2514; AW15): the bag and the amount a deposit
 * takes. Written with its session and never changed.
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
        name = "undeposited_session_drop",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_undeposited_session_drop_movement",
                    columnNames = {"tenant_id", "movement_id"})
        })
public class UndepositedSessionDrop extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "drop_id", nullable = false, columnDefinition = "UUID")
    private UUID dropId;

    @Column(name = "undeposited_session_id", nullable = false, updatable = false)
    private UUID undepositedSessionId;

    /** pos-order's drawer movement. */
    @Column(name = "movement_id", nullable = false, updatable = false)
    private UUID movementId;

    /** The deposit bag; null when the movement carried none. */
    @Column(name = "bag_number", length = 100, updatable = false)
    private String bagNumber;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
