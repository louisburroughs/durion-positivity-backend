package com.positivity.invoice.internal.entity;

import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.RefundStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Data
@Table(name = "refund_records")
@EntityListeners(AuditingEntityListener.class)
public class RefundRecord {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    /** Null for standalone refunds — the original payment is not in the system (#926). */
    @ManyToOne
    @JoinColumn(name = "payment_intent_id")
    private PaymentIntent paymentIntent;

    /** Null for party-anchored standalone refunds with no invoice in the system (#926). */
    @ManyToOne
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    /**
     * Customer party anchor for standalone refunds. Set from the invoice for invoice-anchored
     * standalone refunds, or supplied directly when no invoice exists. At least one of
     * paymentIntent, invoice, or partyId is always present (DB check constraint).
     */
    @Column(name = "party_id", length = 64)
    private String partyId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private RefundReason reason;

    @Column(name = "notes", length = 1024)
    private String notes;

    @Column(name = "gateway_reference", length = 256)
    private String gatewayReference;

    /** Optional correlation id to an external record (e.g. a warranty claim settlement). */
    @Column(name = "external_reference", length = 64)
    private String externalReference;

    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
