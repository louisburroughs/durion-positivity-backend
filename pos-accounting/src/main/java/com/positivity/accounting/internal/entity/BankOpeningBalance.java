package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One bank opening balance command (#2572, OI-10): the journal entry it posted against 3900 Opening Balance
 * Equity, the balances it was given and derived, and the request id and body hash that make it idempotent. The
 * row is never changed; it is the account's standing opening while its entry is POSTED, and a reversed entry
 * (ADR-0047) leaves it as history.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "bank_opening_balance",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_bank_opening_balance_request",
                    columnNames = {"tenant_id", "request_id"})
        })
public class BankOpeningBalance extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "bank_opening_balance_id", nullable = false, columnDefinition = "UUID")
    private UUID bankOpeningBalanceId;

    @Column(name = "gl_account_id", nullable = false, updatable = false)
    private UUID glAccountId;

    /** The cutover date: the entry is dated on it. */
    @Column(name = "as_of_date", nullable = false, updatable = false)
    private LocalDate asOfDate;

    /** The bank's balance at the end of {@code asOfDate}; negative when the account was overdrawn. */
    @Column(name = "statement_balance", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal statementBalance;

    /** Statement balance + deposits in transit − outstanding checks: the ledger balance the entry sets. */
    @Column(name = "book_balance", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal bookBalance;

    /** The ISO 4217 code of both balances and every item (ADR-0067 R-1): the account's, the functional currency. */
    @Column(name = "currency_code", length = 3, nullable = false, updatable = false)
    private String currencyCode;

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private UUID journalEntryId;

    @Column(name = "justification", length = 1000, nullable = false, updatable = false)
    private String justification;

    @Column(name = "actor", length = 50, nullable = false, updatable = false)
    private String actor;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "request_hash", length = 64, nullable = false, updatable = false)
    private String requestHash;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
