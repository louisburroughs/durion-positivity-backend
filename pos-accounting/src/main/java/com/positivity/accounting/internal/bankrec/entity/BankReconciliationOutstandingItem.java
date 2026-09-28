package com.positivity.accounting.internal.bankrec.entity;

import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
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
import java.time.LocalDate;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A non-posting timing difference (SPEC-manual-bank-reconciliation §3.6; story S1, #2300): a
 * deposit in transit, an outstanding check, another ledger timing item, or a bank error the bank
 * will correct. It explains a difference and never posts (O3). The database holds O1 (a ledger line
 * or bank transaction is in at most one {@code OPEN} item); registration, clearing, reaffirmation
 * and clear-in-gap arrive with story S4.
 *
 * <p>An item is open at the end of day d when {@code itemDate ≤ d}, its status is not
 * {@code RELEASED} and {@link #closedOn} is null or later than d.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "bank_reconciliation_outstanding_item")
public class BankReconciliationOutstandingItem extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "outstanding_item_id", nullable = false, columnDefinition = "UUID")
    private UUID outstandingItemId;

    @Column(name = "gl_account_id", nullable = false, columnDefinition = "UUID")
    private UUID glAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 8, nullable = false)
    private OutstandingItemSide side;

    @Column(name = "gl_line_id", columnDefinition = "UUID")
    private UUID glLineId;

    @Column(name = "bank_transaction_id", columnDefinition = "UUID")
    private UUID bankTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_kind", length = 32, nullable = false)
    private OutstandingItemKind itemKind;

    @Column(name = "signed_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal signedAmount;

    @Column(name = "item_date", nullable = false)
    private LocalDate itemDate;

    @Column(name = "registered_in_reconciliation_id", nullable = false, columnDefinition = "UUID")
    private UUID registeredInReconciliationId;

    @Column(name = "registered_by", length = 50, nullable = false)
    private String registeredBy;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "justification", length = 1000)
    private String justification;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private OutstandingItemStatus status = OutstandingItemStatus.OPEN;

    @Column(name = "cleared_in_reconciliation_id", columnDefinition = "UUID")
    private UUID clearedInReconciliationId;

    @Column(name = "cleared_by_match_id", columnDefinition = "UUID")
    private UUID clearedByMatchId;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "cleared_by", length = 50)
    private String clearedBy;

    @Column(name = "clearance_justification", length = 1000)
    private String clearanceJustification;

    @Column(name = "voided_by_journal_entry_id", columnDefinition = "UUID")
    private UUID voidedByJournalEntryId;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by", length = 50)
    private String releasedBy;

    @Column(name = "release_reason", length = 1000)
    private String releaseReason;

    /** The day the item left {@code OPEN} other than by release (§3.6). */
    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(name = "last_reaffirmed_in_reconciliation_id", columnDefinition = "UUID")
    private UUID lastReaffirmedInReconciliationId;

    @Column(name = "last_reaffirmed_by", length = 50)
    private String lastReaffirmedBy;

    @Column(name = "last_reaffirmed_at")
    private Instant lastReaffirmedAt;

    @Column(name = "reaffirm_justification", length = 1000)
    private String reaffirmJustification;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
