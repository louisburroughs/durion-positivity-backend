package com.positivity.accounting.internal.entity;

import jakarta.persistence.*;
import com.positivity.accounting.internal.enums.PaymentStatus;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Entity representing the current payment status of an invoice.
 * This is a denormalized view for quick status lookups.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Table(name = "invoice_status_views")
public class InvoiceStatusView {

    @EqualsAndHashCode.Include
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }

    @Column(nullable = false, unique = true)
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus currentStatus;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalPaid;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal invoiceTotal;

    @Column(nullable = false)
    private Instant lastUpdated;

    @Column
    private String latestTransactionReference;

    public InvoiceStatusView(UUID invoiceId, PaymentStatus currentStatus,
            BigDecimal totalPaid, BigDecimal invoiceTotal) {
        this.invoiceId = invoiceId;
        this.currentStatus = currentStatus;
        this.totalPaid = totalPaid;
        this.invoiceTotal = invoiceTotal;
        this.lastUpdated = Instant.now();
    }
}
