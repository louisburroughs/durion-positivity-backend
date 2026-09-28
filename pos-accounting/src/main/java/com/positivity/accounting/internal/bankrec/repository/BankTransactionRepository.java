package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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

    /** Unexplained rows on an account that has no baseline yet (§4.1). */
    long countByGlAccountIdAndStatusIn(@NonNull UUID glAccountId, @NonNull Collection<BankTransactionStatus> statuses);
}
