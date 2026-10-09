package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * pos-order's copy of one accounting petty-expense category (CAP:550 S16, #2512; ADR-0044 R3, AW18),
 * written only by {@code accounting.petty-expense-category.changed} (S15) and guarded by the fact's
 * {@code aggregateVersion}. Keyed by the fact's aggregate, the accounting category row. The cashier's
 * picker lists the {@code ACTIVE} ones; a petty expense must name one of them.
 */
@Entity
@Table(name = "ext_accounting_petty_expense_category")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtAccountingPettyExpenseCategory extends TenantScopedEntity {

    /** Status a category must have for a new petty expense. */
    public static final String ACTIVE = "ACTIVE";

    @Id
    @Column(name = "petty_expense_category_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID pettyExpenseCategoryId;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "examples", length = 1000)
    private String examples;

    /** {@code ACTIVE} or {@code INACTIVE}. */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "account_code", length = 32)
    private String accountCode;

    @Column(name = "account_name")
    private String accountName;

    /**
     * Whether the tax stated on this category's receipts may be recovered (CAP:550 S32d item 4): the only flag
     * pos-order decides on, to offer regimes. A fact without it maps to false.
     */
    @Column(name = "tax_recoverable", nullable = false)
    private boolean taxRecoverable;

    /** The recovered share, for display only (AW52): pos-accounting reads the share in force from its own history. */
    @Column(name = "recoverable_percent", precision = 5, scale = 2)
    private BigDecimal recoverablePercent;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    /** ArchUnit UUIDv7 rule hook: the id is a UUIDv7 issued by pos-accounting. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
