package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.MatchedPaymentType;
import com.positivity.accounting.internal.enums.SettlementLineMatchStatus;
import com.positivity.accounting.internal.enums.SettlementLineType;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One settlement line (story F1c, issue #963). Matched to a platform {@code ReceivablePayment} (AR,
 * CHARGE lines) or {@code APPayment} (AP, REFUND/payout lines) on {@code grossAmount} within the
 * provider's tolerance (decisions D-11/D-12). Lines the matcher cannot map stay {@link
 * SettlementLineMatchStatus#UNMATCHED} for the review workflow — never mis-posted (decision D-10).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "processor_settlement_line")
public class ProcessorSettlementLine {

    @Id
    @Column(name = "line_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID lineId;

    /** Owning settlement id (plain FK column; the aggregate is loaded via the repository). */
    @Column(name = "settlement_id", length = 128, nullable = false)
    private String settlementId;

    @Column(name = "provider_line_ref", length = 255)
    private String providerLineRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", length = 32, nullable = false)
    private SettlementLineType lineType;

    @Column(name = "gross_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal grossAmount;

    @Column(name = "fee_amount", precision = 19, scale = 4)
    private BigDecimal feeAmount;

    @Column(name = "net_amount", precision = 19, scale = 4)
    private BigDecimal netAmount;

    /** Platform correlation token echoed by the processor (decision D-11). */
    @Column(name = "payment_reference", length = 255)
    private String paymentReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", length = 20, nullable = false)
    private SettlementLineMatchStatus matchStatus;

    @Column(name = "matched_payment_id", columnDefinition = "UUID")
    private UUID matchedPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "matched_payment_type", length = 4)
    private MatchedPaymentType matchedPaymentType;

    @Column(name = "writeoff_reason", length = 500)
    private String writeoffReason;

    @Column(name = "writeoff_journal_entry_id", columnDefinition = "UUID")
    private UUID writeoffJournalEntryId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013). */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
