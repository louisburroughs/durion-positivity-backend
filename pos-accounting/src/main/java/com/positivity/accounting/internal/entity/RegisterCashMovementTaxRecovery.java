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
 * What a petty expense's posting did with one stated regime's tax (CAP:550 S32d item 9): the stated amount, the share
 * in force when it was recorded, what was recovered, and the reason when nothing was. The supplier's registration
 * number is kept as the claim's evidence: INTERNAL under ADR-0072 Decision 1, so it is never logged and {@code
 * toString} leaves it out. Written with the movement's journal entry and never changed.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "register_cash_movement_tax_recovery",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_register_cash_movement_tax_recovery",
                    columnNames = {"tenant_id", "movement_id", "regime"})
        })
public class RegisterCashMovementTaxRecovery extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "tax_recovery_id", nullable = false, columnDefinition = "UUID")
    private UUID taxRecoveryId;

    @Column(name = "movement_id", nullable = false, updatable = false)
    private UUID movementId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private UUID journalEntryId;

    @Column(name = "regime", length = 32, nullable = false, updatable = false)
    private String regime;

    @Column(name = "stated_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal statedAmount;

    /** The category's share in force when the movement was recorded; null when the category was not recoverable. */
    @Column(name = "recoverable_percent", precision = 5, scale = 2, updatable = false)
    private BigDecimal recoverablePercent;

    @Column(name = "recovered_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal recoveredAmount;

    /** Why nothing was recovered; null when something was. */
    @Column(name = "recovery_withheld_reason", length = 40, updatable = false)
    private String recoveryWithheldReason;

    @ToString.Exclude
    @Column(name = "supplier_registration_number", length = 32, updatable = false)
    private String supplierRegistrationNumber;

    @Column(name = "currency_code", length = 3, nullable = false, updatable = false)
    private String currencyCode;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
