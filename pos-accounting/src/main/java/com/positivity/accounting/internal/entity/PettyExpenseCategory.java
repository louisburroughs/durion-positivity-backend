package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.PettyExpenseCategoryStatus;
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
import jakarta.persistence.Version;
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
 * A petty-expense category (#2511; SPEC-accounting-workspace §4.6, AW18): the label and examples the
 * cashier picks from, for the {@code REGISTER_CASH_MOVEMENT} mapping key {@code PETTY_EXPENSE_<code>}.
 * The account a category posts to is that key's effective-dated GL mapping, never a column here.
 *
 * <p>The code is permanent; a category is deactivated, never deleted.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "petty_expense_category",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_petty_expense_category_code",
                    columnNames = {"tenant_id", "code"}),
            @UniqueConstraint(
                    name = "uq_petty_expense_category_mapping_key",
                    columnNames = {"tenant_id", "mapping_key_id"})
        })
public class PettyExpenseCategory extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "petty_expense_category_id", nullable = false, columnDefinition = "UUID")
    private UUID pettyExpenseCategoryId;

    @Column(name = "mapping_key_id", nullable = false, updatable = false)
    private UUID mappingKeyId;

    @Column(name = "code", length = 40, nullable = false, updatable = false)
    private String code;

    @Column(name = "label", length = 100, nullable = false)
    private String label;

    @Column(name = "examples", length = 500)
    private String examples;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private PettyExpenseCategoryStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "modified_by", length = 50, nullable = false)
    private String modifiedBy;
}
