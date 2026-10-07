package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.PettyExpenseCategoryChangeType;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One change to a petty-expense category (#2511): what changed, from what to what, who, when and
 * why. The actor always comes from the security context (ADR-0018). A command's {@code requestId}
 * makes a replay find this row and return the first result.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "petty_expense_category_change",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_petty_expense_category_change_request",
                    columnNames = {"tenant_id", "request_id"})
        })
public class PettyExpenseCategoryChange extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "change_id", nullable = false, columnDefinition = "UUID")
    private UUID changeId;

    @Column(name = "petty_expense_category_id", nullable = false, updatable = false)
    private UUID pettyExpenseCategoryId;

    @Column(name = "code", length = 40, nullable = false, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", length = 20, nullable = false, updatable = false)
    private PettyExpenseCategoryChangeType changeType;

    @Column(name = "old_value", columnDefinition = "TEXT", updatable = false)
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String newValue;

    @Column(name = "actor", length = 50, nullable = false, updatable = false)
    private String actor;

    @Column(name = "justification", length = 1000, nullable = false, updatable = false)
    private String justification;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;
}
