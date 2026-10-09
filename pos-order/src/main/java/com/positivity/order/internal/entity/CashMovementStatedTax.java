package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One regime's tax as stated on a petty-expense receipt (CAP:550 S32d item 5; Order re-confirmation §8): a child row
 * of its {@link CashMovement}, so the database enforces each regime at most once per movement and a positive amount.
 * Copied from the receipt, never calculated, and never changed after it is recorded.
 */
@Entity
@Table(name = "cash_movement_stated_tax")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashMovementStatedTax extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "stated_tax_id", columnDefinition = "UUID")
    private UUID statedTaxId;

    @Column(name = "movement_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID movementId;

    /** The regime code, as pos-tax's country profile names it ({@code [A-Z0-9_]{1,32}}). */
    @Column(name = "regime", nullable = false, length = 32, updatable = false)
    private String regime;

    /** The stated amount, positive, at the drawer currency's exponent. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;
}
