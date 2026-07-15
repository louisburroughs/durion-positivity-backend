package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.warranty.internal.enums.SettlementStatus;
import com.positivity.warranty.internal.enums.SettlementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * How the customer was made whole (PRD §3.6) — a claim can have more than one settlement
 * (e.g. prorated credit and a replacement workorder). {@code replacementWorkorderId} is a link
 * only: pos-workorder stays unaware of warranty. Invoice/refund ids are set when warranty
 * calls pos-invoice.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "claim_settlement", indexes = @Index(name = "idx_csettlement_claim", columnList = "claim_id"))
@EntityListeners(AuditingEntityListener.class)
public class ClaimSettlement {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type", nullable = false, length = 32)
    private SettlementType settlementType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SettlementStatus status = SettlementStatus.PENDING;

    /** Link to the replacement workorder created through the normal flow. */
    @Column(name = "replacement_workorder_id")
    private UUID replacementWorkorderId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    /** pos-invoice InvoiceAdjustment id when settlement is an invoice credit. */
    @Column(name = "invoice_adjustment_id")
    private UUID invoiceAdjustmentId;

    /** pos-invoice RefundRecord id when settlement is a refund. */
    @Column(name = "refund_record_id")
    private UUID refundRecordId;

    /** What coverage pays. */
    @Column(name = "covered_amount", precision = 19, scale = 4)
    private BigDecimal coveredAmount;

    /** What the customer still owes (proration remainder, upcharge, disposal fees). */
    @Column(name = "customer_amount", precision = 19, scale = 4)
    private BigDecimal customerAmount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;
}
