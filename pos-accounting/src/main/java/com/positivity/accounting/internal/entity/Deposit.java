package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.DepositStatus;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
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
 * A bank deposit of drawer cash (CAP:550 S18, #2514; SPEC-accounting-workspace §4.5, §7.1 "Record / reverse deposit";
 * AW10): the {@code BANK_DEPOSIT} entry it posted, the request id and body hash that make the command idempotent, and,
 * once its entry is reversed (ADR-0047), the reversal. Never edited otherwise: a wrong deposit is reversed and
 * recorded again.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "deposit",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_deposit_request",
                    columnNames = {"tenant_id", "request_id"}),
            @UniqueConstraint(
                    name = "uq_deposit_reversal_request",
                    columnNames = {"tenant_id", "reversal_request_id"})
        })
public class Deposit extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "deposit_id", nullable = false, columnDefinition = "UUID")
    private UUID depositId;

    /** The bank account the drawer cash went to: an active, reconcilable BANK_CASH account. */
    @Column(name = "bank_gl_account_id", nullable = false, updatable = false)
    private UUID bankGlAccountId;

    /** The account's code when the deposit was recorded. */
    @Column(name = "bank_account_code", length = 20, nullable = false, updatable = false)
    private String bankAccountCode;

    /** The day the cash reached the bank: the entry's date. */
    @Column(name = "deposit_date", nullable = false, updatable = false)
    private LocalDate depositDate;

    /** The total of the sessions' bank drops: the one bank debit line. */
    @Column(name = "amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal amount;

    /** The sessions' expected cash: what leaves 1090 Undeposited Funds. */
    @Column(name = "expected_cash", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal expectedCash;

    /** The sessions' clearing net (debit positive): what the deposit clears from 1095 Register Cash Clearing. */
    @Column(name = "clearing_net", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal clearingNet;

    /** The ISO 4217 code of every amount (ADR-0067 R-1): the functional currency. */
    @Column(name = "currency_code", length = 3, nullable = false, updatable = false)
    private String currencyCode;

    @Column(name = "deposit_slip_reference", length = 100, updatable = false)
    private String depositSlipReference;

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private UUID journalEntryId;

    /** The entry's number (ADR-0064), the business reference a replay answers with. */
    @Column(name = "journal_entry_number", length = 20, nullable = false, updatable = false)
    private String journalEntryNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private DepositStatus status;

    /** Why the entry posted into a closed period, when it did. */
    @Column(name = "override_justification", length = 1000, updatable = false)
    private String overrideJustification;

    /** Who recorded it, from the security context (ADR-0018). */
    @Column(name = "recorded_by", length = 50, nullable = false, updatable = false)
    private String recordedBy;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "request_hash", length = 64, nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "reversal_journal_entry_id")
    private UUID reversalJournalEntryId;

    @Column(name = "reversal_journal_entry_number", length = 20)
    private String reversalJournalEntryNumber;

    @Column(name = "reversal_date")
    private LocalDate reversalDate;

    @Column(name = "reversal_reason", length = 1000)
    private String reversalReason;

    @Column(name = "reversal_override_justification", length = 1000)
    private String reversalOverrideJustification;

    @Column(name = "reversed_by", length = 50)
    private String reversedBy;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    /** The Reverse deposit command's request id; null for a deposit reversed as a journal entry. */
    @Column(name = "reversal_request_id")
    private UUID reversalRequestId;

    @Column(name = "reversal_request_hash", length = 64)
    private String reversalRequestHash;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private Instant updatedAt;
}
