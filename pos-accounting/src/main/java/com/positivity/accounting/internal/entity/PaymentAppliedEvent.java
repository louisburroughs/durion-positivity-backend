package com.positivity.accounting.internal.entity;

import jakarta.persistence.*;
import com.positivity.accounting.internal.enums.PaymentStatus;
import com.positivity.shared.id.UUIDv7Id;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Entity representing a payment applied to an invoice.
 * Stores payment transaction details for auditing and status calculation.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(name = "payment_applied_events")
public class PaymentAppliedEvent {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
    }

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal paymentAmount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal invoiceTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private Instant timestamp;

    @Column
    private String idempotencyKey;

    @Column
    private String transactionReference;

    public PaymentAppliedEvent(UUID invoiceId, String transactionReference,
            BigDecimal paymentAmount, BigDecimal invoiceTotal,
            PaymentStatus status, String idempotencyKey) {
        this.invoiceId = invoiceId;
        this.transactionReference = transactionReference;
        this.paymentAmount = paymentAmount;
        this.invoiceTotal = invoiceTotal;
        this.status = status;
        this.timestamp = Instant.now();
        this.idempotencyKey = idempotencyKey;
    }
}
