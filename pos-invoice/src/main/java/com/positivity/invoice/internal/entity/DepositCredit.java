package com.positivity.invoice.internal.entity;

import com.positivity.invoice.internal.enums.DepositCreditStatus;
import com.positivity.invoice.internal.enums.DepositSourceType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A deposit / down-payment credit (odoo-parity story E4, issue #1085, spec R7.4). The dedicated
 * artifact that resolves gate V2: unlike an {@link InvoiceAdjustment}, it carries typed source
 * provenance ({@link #sourceType}/{@link #sourceId}), an {@link #originalAmount}/
 * {@link #remainingBalance} pair for partial draw-down, and an application-audit trail
 * ({@link #applications}) so the full chain from the deposit-take order to each settlement invoice
 * is recoverable.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "deposit_credit")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepositCredit {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "deposit_credit_id", columnDefinition = "UUID")
    private UUID depositCreditId;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private DepositSourceType sourceType;

    @Column(name = "source_id", nullable = false, columnDefinition = "UUID")
    private UUID sourceId;

    /** The pos-order sale that took the deposit; unique so a replayed take is idempotent. */
    @Column(name = "order_id", nullable = false, columnDefinition = "UUID", unique = true)
    private UUID orderId;

    @Column(name = "party_id", length = 64)
    private String partyId;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "USD";

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal originalAmount;

    @Column(name = "remaining_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal remainingBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DepositCreditStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "depositCredit", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<DepositCreditApplication> applications = new ArrayList<>();

    public void addApplication(@NonNull DepositCreditApplication application) {
        applications.add(application);
        application.setDepositCredit(this);
    }
}
