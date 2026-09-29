package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link BankTransaction} rows (SPEC §3.2; stories S1 #2300, S2 #2301). The list
 * filters use specifications rather than a nullable-parameter JPQL query (issues #1891, #1961).
 */
public interface BankTransactionRepository
        extends JpaRepository<BankTransaction, UUID>, JpaSpecificationExecutor<BankTransaction> {

    /** The transactions a statement carried, in source-file order (F2's statement lines). */
    @NonNull
    List<BankTransaction> findByStatementIdOrderBySourceRowNumberAsc(@NonNull UUID statementId);

    /** U3: the row a source already reported under this id ({@code sourceRef} may be null). */
    Optional<BankTransaction> findFirstByGlAccountIdAndSourceKindAndSourceRefAndSourceTransactionId(
            @NonNull UUID glAccountId,
            @NonNull SourceKind sourceKind,
            UUID sourceRef,
            @NonNull String sourceTransactionId);

    /** R1: rows on the account with this fingerprint, earliest first, excluding the given statuses. */
    @NonNull
    List<BankTransaction> findByGlAccountIdAndFingerprintAndStatusNotInOrderByFirstObservedAtAscBankTransactionIdAsc(
            @NonNull UUID glAccountId,
            @NonNull String fingerprint,
            @NonNull Collection<BankTransactionStatus> excluded);

    /** R1 for a whole file at once: rows on the account with any of these fingerprints (story S3, #2302). */
    @NonNull
    List<BankTransaction> findByGlAccountIdAndFingerprintInAndStatusNotIn(
            @NonNull UUID glAccountId,
            @NonNull Collection<String> fingerprints,
            @NonNull Collection<BankTransactionStatus> excluded);

    long countByStatementId(@NonNull UUID statementId);

    long countByStatementIdAndStatus(@NonNull UUID statementId, @NonNull BankTransactionStatus status);

    /** Unexplained rows dated on or after the baseline (§4.1). */
    long countByGlAccountIdAndStatusInAndTransactionDateGreaterThanEqual(
            @NonNull UUID glAccountId, @NonNull Collection<BankTransactionStatus> statuses, @NonNull LocalDate from);

    /** The account's rows in the given statuses dated in {@code [from, to]} (§3.7, §4.6; S4, #2303). */
    @NonNull
    List<BankTransaction> findByGlAccountIdAndTransactionDateBetweenAndStatusIn(
            @NonNull UUID glAccountId,
            @NonNull LocalDate from,
            @NonNull LocalDate to,
            @NonNull Collection<BankTransactionStatus> statuses);

    /** The account's rows in the given statuses dated on or before {@code to} (no baseline, §3.7; S4). */
    @NonNull
    List<BankTransaction> findByGlAccountIdAndTransactionDateLessThanEqualAndStatusIn(
            @NonNull UUID glAccountId, @NonNull LocalDate to, @NonNull Collection<BankTransactionStatus> statuses);

    /** Near-duplicate candidates (§4.5; S4): same account and signed amount, dated in the window, not in {@code excluded}. */
    @NonNull
    List<BankTransaction> findByGlAccountIdAndSignedAmountAndTransactionDateBetweenAndStatusNotIn(
            @NonNull UUID glAccountId,
            java.math.@NonNull BigDecimal signedAmount,
            @NonNull LocalDate from,
            @NonNull LocalDate to,
            @NonNull Collection<BankTransactionStatus> excluded);

    /** The given rows, row-locked for the rest of the transaction (match vs. outstanding item, O1/U4; S4). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM BankTransaction t WHERE t.bankTransactionId IN :ids")
    @NonNull
    List<BankTransaction> lockByIds(@Param("ids") @NonNull Collection<UUID> ids);

    /** Unexplained rows on an account that has no baseline yet (§4.1). */
    long countByGlAccountIdAndStatusIn(@NonNull UUID glAccountId, @NonNull Collection<BankTransactionStatus> statuses);

    /**
     * Rows in {@code statuses} per account, counting only rows dated on or after the account's
     * baseline when it has one (§4.1) — one grouped query for a page of the bank-account list.
     */
    @Query(
            "SELECT new com.positivity.accounting.internal.bankrec.repository.AccountCount(t.glAccountId, COUNT(t)) FROM BankTransaction t"
                    + " LEFT JOIN BankAccountProfile p ON p.glAccountId = t.glAccountId"
                    + " WHERE t.glAccountId IN :ids AND t.status IN :statuses"
                    + " AND (p.reconciliationBaselineDate IS NULL OR t.transactionDate >= p.reconciliationBaselineDate)"
                    + " GROUP BY t.glAccountId")
    @NonNull
    List<AccountCount> countSinceBaselineByGlAccountIdIn(
            @Param("ids") @NonNull Collection<UUID> glAccountIds,
            @Param("statuses") @NonNull Collection<BankTransactionStatus> statuses);

    /** Each statement's row count and {@code flagged} row count — one grouped query for a page (§6.3). */
    @Query("SELECT new com.positivity.accounting.internal.bankrec.repository.StatementCounts(t.statementId, COUNT(t),"
            + " SUM(CASE WHEN t.status = :flagged THEN 1L ELSE 0L END)) FROM BankTransaction t"
            + " WHERE t.statementId IN :ids GROUP BY t.statementId")
    @NonNull
    List<StatementCounts> countByStatementIdIn(
            @Param("ids") @NonNull Collection<UUID> statementIds,
            @Param("flagged") @NonNull BankTransactionStatus flagged);

    /**
     * Rows that arrived after their window was approved, dated in {@code [from, to]} and still in one of the given
     * states — close readiness's {@code LATE_BANK_TRANSACTIONS} (SPEC §5.3, D10; story S6, #2305).
     */
    @NonNull
    List<BankTransaction> findByGlAccountIdAndArrivedAfterApprovalTrueAndTransactionDateBetweenAndStatusIn(
            @NonNull UUID glAccountId,
            @NonNull LocalDate from,
            @NonNull LocalDate to,
            @NonNull Collection<BankTransactionStatus> statuses);
}
