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
import jakarta.persistence.Version;
import java.math.BigDecimal;
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
 * A petty-expense category's tax recovery as it stands now (CAP:550 S32d item 4): whether the tax stated on its
 * receipts is recovered, and which share. A category without a row is not recoverable. What a posting recovers is the
 * share in force when the movement was recorded, read from {@link PettyExpenseCategoryTaxSettingChange} (AW52).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "petty_expense_category_tax_setting",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_petty_expense_category_tax_setting_code",
                    columnNames = {"tenant_id", "code"})
        })
public class PettyExpenseCategoryTaxSetting extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "tax_setting_id", nullable = false, columnDefinition = "UUID")
    private UUID taxSettingId;

    @Column(name = "petty_expense_category_id", nullable = false, updatable = false)
    private UUID pettyExpenseCategoryId;

    @Column(name = "code", length = 40, nullable = false, updatable = false)
    private String code;

    @Column(name = "tax_recoverable", nullable = false)
    private boolean taxRecoverable;

    /** The recovered share in (0, 100] when recoverable; null when not. */
    @Column(name = "recoverable_percent", precision = 5, scale = 2)
    private BigDecimal recoverablePercent;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

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
