package com.positivity.accounting.internal.bankrec.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Bank-side member of a reconciliation match (SPEC-manual-bank-reconciliation §3.4; story S1,
 * #2300): one bank transaction in one match header. It replaces F2's
 * {@code bank_reconciliation_line.match_id}. Unmatching clears {@link #active} instead of deleting
 * the row; the partial unique {@code (tenant_id, bank_transaction_id) WHERE active} keeps a bank
 * transaction in at most one live match (U4).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@IdClass(BankReconciliationBankMatch.Key.class)
@Table(name = "bank_reconciliation_bank_match")
public class BankReconciliationBankMatch extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @AssignedIdentifier("the match header's own UUIDv7; this membership row is keyed by the header it belongs to")
    @Column(name = "match_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID matchId;

    @EqualsAndHashCode.Include
    @Id
    @AssignedIdentifier("the bank transaction's own UUIDv7; this membership row is keyed by the transaction it links")
    @Column(name = "bank_transaction_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID bankTransactionId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public BankReconciliationBankMatch(UUID matchId, UUID bankTransactionId) {
        this.matchId = matchId;
        this.bankTransactionId = bankTransactionId;
    }

    /** Composite primary key {@code (match_id, bank_transaction_id)}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID matchId;
        private UUID bankTransactionId;
    }
}
