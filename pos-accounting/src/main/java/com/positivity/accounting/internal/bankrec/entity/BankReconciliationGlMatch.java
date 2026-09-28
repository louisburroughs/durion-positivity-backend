package com.positivity.accounting.internal.bankrec.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Ledger-side member of a reconciliation match (Story F2, issue #965; story S1, #2300).
 * Records which {@code journal_entry_line} rows a match ({@link #matchId}, the
 * {@code bank_reconciliation_match} header) linked to its bank transactions, with the line's
 * signed amount (debit − credit on the reconciled account). Unmatching clears {@link #active}
 * instead of deleting the row, which releases the GL line for another match.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "bank_reconciliation_gl_match",
        indexes = {
            @Index(name = "idx_bank_reconciliation_gl_match_recon", columnList = "reconciliation_id"),
            @Index(name = "idx_bank_reconciliation_gl_match_match", columnList = "match_id")
        })
public class BankReconciliationGlMatch extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "reconciliation_id", columnDefinition = "UUID", nullable = false)
    private UUID reconciliationId;

    @Column(name = "match_id", columnDefinition = "UUID", nullable = false)
    private UUID matchId;

    @Column(name = "gl_line_id", columnDefinition = "UUID", nullable = false)
    private UUID glLineId;

    @Column(name = "signed_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal signedAmount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Denormalized "the header is PROPOSED or ACCEPTED" flag (SPEC §3.4, §6.4). The partial unique
     * {@code (tenant_id, gl_line_id) WHERE active} keeps a ledger line in at most one live match while
     * unmatched history survives (M7: matches are never deleted).
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
