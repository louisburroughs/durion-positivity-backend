package com.positivity.accounting.internal.bankrec.entity;

import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchOrigin;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
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
import java.util.List;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Header of a reconciliation match (SPEC-manual-bank-reconciliation §3.4; story S1, #2300). Its
 * members are {@link BankReconciliationGlMatch} (ledger side) and {@link BankReconciliationBankMatch}
 * (bank side). A match is never deleted (M7): unmatching moves it to {@code UNMATCHED} and clears
 * its members' {@code active} flag.
 *
 * <p>Story S4 (#2303) refuses a group with more than one member on both sides (M2,
 * {@code MATCH_CARDINALITY_NOT_ALLOWED}); {@link #matchKind} is null only on a row no longer produced.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "bank_reconciliation_match")
public class BankReconciliationMatch extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "match_id", nullable = false, columnDefinition = "UUID")
    private UUID matchId;

    @Column(name = "reconciliation_id", nullable = false, columnDefinition = "UUID")
    private UUID reconciliationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_kind", length = 16)
    private MatchKind matchKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 16, nullable = false)
    private MatchState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", length = 8, nullable = false)
    private MatchOrigin origin;

    /** 0–100 for a rule proposal; null for a user match. */
    @Column(name = "confidence_score")
    private Integer confidenceScore;

    /** Why a rule proposed the match ({@code EXACT_AMOUNT}, {@code DATE_IN_WINDOW}, …). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons")
    private List<String> reasons;

    @Column(name = "bank_total", precision = 19, scale = 4)
    private BigDecimal bankTotal;

    @Column(name = "ledger_total", precision = 19, scale = 4)
    private BigDecimal ledgerTotal;

    /** {@code abs(bankTotal − ledgerTotal)}; never negative (M1). */
    @Column(name = "tolerance_used", precision = 19, scale = 4)
    private BigDecimal toleranceUsed;

    @Column(name = "justification", length = 1000)
    private String justification;

    @Column(name = "proposed_by", length = 50)
    private String proposedBy;

    @Column(name = "proposed_at")
    private Instant proposedAt;

    @Column(name = "accepted_by", length = 50)
    private String acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "rejected_by", length = 50)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "unmatched_by", length = 50)
    private String unmatchedBy;

    @Column(name = "unmatched_at")
    private Instant unmatchedAt;

    /** Free text, or {@code RESIDUAL_SETTLED} / {@code RECONCILIATION_CANCELLED} when the system unmatches. */
    @Column(name = "unmatch_reason", length = 1000)
    private String unmatchReason;

    /** The match a residual settlement replaced (§4.6). */
    @Column(name = "replaces_match_id", columnDefinition = "UUID")
    private UUID replacesMatchId;

    @Column(name = "broken_by_journal_entry_id", columnDefinition = "UUID")
    private UUID brokenByJournalEntryId;

    /** The caller's UUIDv7 {@code requestId} of a human match; a replay finds this header (§6.3; S4, #2303). */
    @Column(name = "request_id", columnDefinition = "UUID")
    private UUID requestId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
