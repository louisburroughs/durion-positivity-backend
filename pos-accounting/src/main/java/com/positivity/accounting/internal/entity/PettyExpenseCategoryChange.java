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
import java.time.LocalDate;
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

    // ---- the change as values (#2675): filled when the row is written; null on rows written before V27 and on
    // DEACTIVATE rows, whose values are the status codes in old_value / new_value.

    @Column(name = "old_label", length = 100, updatable = false)
    private String oldLabel;

    @Column(name = "new_label", length = 100, updatable = false)
    private String newLabel;

    @Column(name = "old_examples", length = 500, updatable = false)
    private String oldExamples;

    @Column(name = "new_examples", length = 500, updatable = false)
    private String newExamples;

    @Column(name = "old_account_number", length = 20, updatable = false)
    private String oldAccountNumber;

    /** The replaced account's name at the moment of the change: the history is the audit trail. */
    @Column(name = "old_account_name", length = 100, updatable = false)
    private String oldAccountName;

    @Column(name = "new_account_number", length = 20, updatable = false)
    private String newAccountNumber;

    @Column(name = "new_account_name", length = 100, updatable = false)
    private String newAccountName;

    /** CREATE: the business date the first mapping applies from; REMAP: the requested {@code effectiveFrom}. */
    @Column(name = "effective_from", updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "actor", length = 50, nullable = false, updatable = false)
    private String actor;

    @Column(name = "justification", length = 1000, nullable = false, updatable = false)
    private String justification;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    /** The command's response as first returned, for a replay of its requestId. */
    @Column(name = "response_json", columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;
}
