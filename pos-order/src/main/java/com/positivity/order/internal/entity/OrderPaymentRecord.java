package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Settlement ledger entry for an order (parity story C3, spec R4.2–R4.4): one row per applied
 * {@code payment.payment.settled} (SETTLED) or {@code payment.payment.reversed} (REVERSED)
 * envelope. The order's {@code amountPaid} is Σ SETTLED − Σ REVERSED; the cancellation saga
 * iterates net-settled entries to reverse them (spec R4.6). Event replays are deduplicated by
 * envelope eventId via {@code processed_events}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "order_payment_record")
public class OrderPaymentRecord {

    public enum RecordType {
        SETTLED,
        REVERSED
    }

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "payment_record_id", columnDefinition = "UUID")
    private UUID paymentRecordId;

    @Column(name = "order_id", nullable = false, columnDefinition = "UUID")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_type", nullable = false, length = 16)
    private RecordType recordType;

    /** pos-invoice PaymentIntent id; null for standalone refunds with no gateway leg. */
    @Column(name = "payment_intent_id", columnDefinition = "UUID")
    private UUID paymentIntentId;

    /** pos-invoice RefundRecord id for REVERSED entries. */
    @Column(name = "refund_id", columnDefinition = "UUID")
    private UUID refundId;

    /** CASH / CARD / ON_ACCOUNT / OTHER. */
    @Column(name = "method_type", nullable = false, length = 32)
    private String methodType;

    /** Positive amount; sign is carried by {@link RecordType}. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** Processor/gateway reference, when available. */
    @Column(name = "reference", length = 128)
    private String reference;

    /** When the settlement/reversal committed at pos-invoice. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
