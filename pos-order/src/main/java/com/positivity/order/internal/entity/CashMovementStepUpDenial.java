package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One refused manager approval on a drawer (CAP:550 S16, #2512; AW31): the step-up refused the sign-in
 * name entered, or the person it named may not approve at the drawer. After {@code
 * pos.order.session.max-denied-approvals} rows for one session and sign-in name, pos-order stops asking
 * pos-security-service for that name, so a register cannot run a manager into the sign-in lockout. The
 * password is never stored.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "cash_movement_step_up_denial")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashMovementStepUpDenial extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "denial_id", columnDefinition = "UUID")
    private UUID denialId;

    @Column(name = "session_id", nullable = false, columnDefinition = "UUID", updatable = false)
    private UUID sessionId;

    /** The manager sign-in name as entered, trimmed and lower case. */
    @Column(name = "approver_username", nullable = false, updatable = false)
    private String approverUsername;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
