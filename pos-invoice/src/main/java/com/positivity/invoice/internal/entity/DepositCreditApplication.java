package com.positivity.invoice.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One draw-down of a {@link DepositCredit} against a settlement invoice (odoo-parity story E4,
 * issue #1085). Append-only audit row: the sum of a credit's applications equals its consumed
 * amount, and each row names the invoice the credit was applied to.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "deposit_credit_application")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepositCreditApplication {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "application_id", columnDefinition = "UUID")
    private UUID applicationId;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "deposit_credit_id", nullable = false)
    @ToString.Exclude
    private DepositCredit depositCredit;

    @Column(name = "invoice_id", nullable = false, columnDefinition = "UUID")
    private UUID invoiceId;

    @Column(name = "amount_applied", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountApplied;

    @CreatedDate
    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;
}
