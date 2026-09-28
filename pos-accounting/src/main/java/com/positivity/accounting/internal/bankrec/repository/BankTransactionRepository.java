package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository for {@link BankTransaction} rows (SPEC §3.2; stories S1 #2300, S2 #2301). The list
 * filters use specifications rather than a nullable-parameter JPQL query (issues #1891, #1961).
 */
public interface BankTransactionRepository
        extends JpaRepository<BankTransaction, UUID>, JpaSpecificationExecutor<BankTransaction> {

    /** The transactions a statement carried, in source-file order (F2's statement lines). */
    List<BankTransaction> findByStatementIdOrderBySourceRowNumberAsc(UUID statementId);

    /** U3: the row a source already reported under this id ({@code sourceRef} may be null). */
    Optional<BankTransaction> findFirstByGlAccountIdAndSourceKindAndSourceRefAndSourceTransactionId(
            UUID glAccountId, SourceKind sourceKind, UUID sourceRef, String sourceTransactionId);

    /** R1: rows on the account with this fingerprint, earliest first, excluding the given statuses. */
    List<BankTransaction> findByGlAccountIdAndFingerprintAndStatusNotInOrderByFirstObservedAtAscBankTransactionIdAsc(
            UUID glAccountId, String fingerprint, Collection<BankTransactionStatus> excluded);

    long countByStatementId(UUID statementId);

    long countByStatementIdAndStatus(UUID statementId, BankTransactionStatus status);

    /** Unexplained rows dated on or after the baseline (§4.1). */
    long countByGlAccountIdAndStatusInAndTransactionDateGreaterThanEqual(
            UUID glAccountId, Collection<BankTransactionStatus> statuses, LocalDate from);

    /** Unexplained rows on an account that has no baseline yet (§4.1). */
    long countByGlAccountIdAndStatusIn(UUID glAccountId, Collection<BankTransactionStatus> statuses);
}
