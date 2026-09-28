package com.positivity.accounting.internal.bankrec.entity;

import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.FeedChange;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
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
 * One line the bank reports (SPEC-manual-bank-reconciliation §3.2; story S1, #2300). It replaces
 * F2's {@code bank_reconciliation_line}: a bank transaction exists independently of any
 * reconciliation and is not a journal entry — it is evidence that cash moved at the bank.
 *
 * <p>Amounts are stored exactly as delivered ({@code signedAmount}, positive = cash in; R3). The
 * database holds U3 (a source id is unique per account and source) and U4 (at most one active match,
 * on {@code bank_reconciliation_bank_match}). Normalization, fingerprinting and duplicate flagging
 * arrive with the intake in story S2.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "bank_transaction")
public class BankTransaction extends TenantScopedEntity {

    private static final String SYSTEM = "SYSTEM";

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "bank_transaction_id", nullable = false, columnDefinition = "UUID")
    private UUID bankTransactionId;

    @Column(name = "gl_account_id", nullable = false, columnDefinition = "UUID")
    private UUID glAccountId;

    /** The statement that carried this row; null for feed rows (phase 2). */
    @Column(name = "statement_id", columnDefinition = "UUID")
    private UUID statementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", length = 16, nullable = false)
    private SourceKind sourceKind;

    @Column(name = "source_ref", columnDefinition = "UUID")
    private UUID sourceRef;

    @Column(name = "connector_code", length = 64)
    private String connectorCode;

    @Column(name = "source_transaction_id", length = 128)
    private String sourceTransactionId;

    /** 1-based position in the source file (F2's line number). */
    @Column(name = "source_row_number")
    private Integer sourceRowNumber;

    /** The pending row a posted one replaced (D19). */
    @Column(name = "supersedes_bank_transaction_id", columnDefinition = "UUID")
    private UUID supersedesBankTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_state", length = 16, nullable = false)
    private SettlementState settlementState = SettlementState.POSTED;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "authorized_date")
    private LocalDate authorizedDate;

    @Column(name = "signed_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal signedAmount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "original_description", length = 1000)
    private String originalDescription;

    @Column(name = "normalized_description", length = 500)
    private String normalizedDescription;

    @Column(name = "reference", length = 255)
    private String reference;

    @Column(name = "check_number", length = 32)
    private String checkNumber;

    @Column(name = "counterparty_name", length = 255)
    private String counterpartyName;

    @Column(name = "category_hint", length = 64)
    private String categoryHint;

    /** SHA-256 hex of the dedupe key (§3.2); computed by the intake (S2). */
    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24, nullable = false)
    private BankTransactionStatus status = BankTransactionStatus.UNMATCHED;

    @Column(name = "duplicate_of_bank_transaction_id", columnDefinition = "UUID")
    private UUID duplicateOfBankTransactionId;

    /** Set when a feed delivers the row inside a FINALIZED window (D10, phase 2). */
    @Column(name = "arrived_after_approval", nullable = false)
    private boolean arrivedAfterApproval;

    @Column(name = "exclusion_reason", length = 1000)
    private String exclusionReason;

    @Column(name = "excluded_by", length = 50)
    private String excludedBy;

    @Column(name = "excluded_at")
    private Instant excludedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "feed_change", length = 16)
    private FeedChange feedChange;

    @Column(name = "first_observed_at")
    private Instant firstObservedAt;

    @Column(name = "last_observed_at")
    private Instant lastObservedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onPrePersist() {
        if (createdBy == null) {
            createdBy = SecurityContextHelper.isAuthenticated()
                    ? SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM)
                    : SYSTEM;
        }
    }
}
