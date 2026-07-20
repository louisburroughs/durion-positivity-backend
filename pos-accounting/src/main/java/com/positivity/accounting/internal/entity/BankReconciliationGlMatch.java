package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
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
 * A posted GL journal-entry line consumed by a match set (Story F2, issue #965).
 * Records which {@code journal_entry_line} rows a match (grouped by {@link #matchId})
 * linked to its statement lines, with the line's signed amount (debit − credit on
 * the reconciled account), so a match can be reversed and the GL lines released.
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
public class BankReconciliationGlMatch {

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
}
