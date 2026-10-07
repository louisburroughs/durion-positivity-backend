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
 * One changed setting of the drawer policy (CAP:550 S16, #2512; §4.6 "every change audited"): the
 * setting, its old and new value, the actor and the justification. A PUT that changes three settings
 * writes three rows; a PUT that changes nothing writes none. Rows are never changed.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "session_policy_change")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionPolicyChange extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "change_id", columnDefinition = "UUID")
    private UUID changeId;

    /** e.g. {@code PETTY_EXPENSE_LIMIT}, {@code OVER_SHORT_TOLERANCE}. */
    @Column(name = "setting", nullable = false, length = 32, updatable = false)
    private String setting;

    @Column(name = "old_value", length = 32, updatable = false)
    private String oldValue;

    @Column(name = "new_value", length = 32, updatable = false)
    private String newValue;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Column(name = "justification", nullable = false, length = 1000, updatable = false)
    private String justification;

    /** The policy version the change produced. */
    @Column(name = "policy_version", nullable = false, updatable = false)
    private Long policyVersion;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
