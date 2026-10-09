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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One change to a petty-expense category's tax recovery (CAP:550 S32d item 4), in force from {@link #effectiveFrom}:
 * from what to what, who in which role, and why. Never updated or deleted, so the share in force at any instant can
 * be read back (AW52). The actor comes from the security context (ADR-0018); a command's {@code requestId} makes a
 * replay find this row and return the first result.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "petty_expense_category_tax_setting_change",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_petty_expense_category_tax_setting_change_request",
                    columnNames = {"tenant_id", "request_id"})
        })
public class PettyExpenseCategoryTaxSettingChange extends TenantScopedEntity {

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

    /** The instant from which the new values are in force. */
    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    @Column(name = "old_tax_recoverable", updatable = false)
    private Boolean oldTaxRecoverable;

    @Column(name = "old_recoverable_percent", precision = 5, scale = 2, updatable = false)
    private BigDecimal oldRecoverablePercent;

    @Column(name = "new_tax_recoverable", nullable = false, updatable = false)
    private boolean newTaxRecoverable;

    @Column(name = "new_recoverable_percent", precision = 5, scale = 2, updatable = false)
    private BigDecimal newRecoverablePercent;

    @Column(name = "actor", length = 50, nullable = false, updatable = false)
    private String actor;

    @Column(name = "actor_role", length = 50, updatable = false)
    private String actorRole;

    @Column(name = "justification", length = 1000, nullable = false, updatable = false)
    private String justification;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    /** The command's response as first returned, for a replay of its requestId. */
    @Column(name = "response_json", columnDefinition = "TEXT", updatable = false)
    private String responseJson;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
