package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.SettlementStatus;
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
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A processor settlement (payout), materialized from the normalized {@code SettlementReportedV1}
 * contract (story F1c, issue #963, decisions D-9…D-14). Accounting-owned: matching results and the
 * posted journal-entry link live here, keyed by the provider's own {@code settlementId}.
 *
 * <p>The header invariant {@code grossAmount = feeAmount + netAmount} is guaranteed by the contract
 * record before persistence. One batched JE is posted per settlement (decision D-13); {@code status}
 * flips {@code RECEIVED} → {@code POSTED} when that entry is written.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "processor_settlement")
public class ProcessorSettlement {

    /** Provider-issued settlement/payout identifier and aggregate id (decision D-10). */
    @Id
    @Column(name = "settlement_id", length = 128, nullable = false, updatable = false)
    private String settlementId;

    /** {@code SettlementReportedV1.eventId} — the ingest idempotency key. */
    @Column(name = "event_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "provider_code", length = 64, nullable = false)
    private String providerCode;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "gross_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal grossAmount;

    @Column(name = "fee_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal feeAmount;

    @Column(name = "net_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal netAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private SettlementStatus status;

    /** Sum of gross over MATCHED lines — the amount cleared from Undeposited Funds (decision D-13). */
    @Column(name = "matched_gross", precision = 19, scale = 4, nullable = false)
    private BigDecimal matchedGross;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "unmatched_count", nullable = false)
    private int unmatchedCount;

    /** Posted batched settlement JE (null until POSTED). */
    @Column(name = "journal_entry_id", columnDefinition = "UUID")
    private UUID journalEntryId;

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
