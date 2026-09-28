package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository for {@link BankStatement} headers (SPEC §3.1; stories S1 #2300, S2 #2301). The list
 * filters use specifications rather than a nullable-parameter JPQL query (issues #1891, #1961).
 */
public interface BankStatementRepository
        extends JpaRepository<BankStatement, UUID>, JpaSpecificationExecutor<BankStatement> {

    /** U1: the statement already holding exactly this window on the account. */
    Optional<BankStatement> findFirstByGlAccountIdAndStatusAndStartDateAndEndDate(
            UUID glAccountId, BankStatementStatus status, LocalDate startDate, LocalDate endDate);

    /**
     * U2: the earliest statement whose window overlaps {@code [startDate, endDate]} — one that starts
     * on or before {@code endDate} and ends on or after {@code startDate}.
     */
    Optional<BankStatement>
            findFirstByGlAccountIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAsc(
                    UUID glAccountId, BankStatementStatus status, LocalDate endDate, LocalDate startDate);

    /** E2: the statement a new window starting at {@code startDate} would continue. */
    Optional<BankStatement> findFirstByGlAccountIdAndStatusAndEndDateLessThanOrderByEndDateDesc(
            UUID glAccountId, BankStatementStatus status, LocalDate startDate);

    /** The baseline (§3.1): the latest-starting statement that carries a gap acknowledgement. */
    Optional<BankStatement> findFirstByGlAccountIdAndStatusAndGapAcknowledgementIsNotNullOrderByStartDateDesc(
            UUID glAccountId, BankStatementStatus status);

    /** The coverage frontier (§4.1): the latest-ending statement. */
    Optional<BankStatement> findFirstByGlAccountIdAndStatusOrderByEndDateDesc(
            UUID glAccountId, BankStatementStatus status);

    /** The statement a manual-statement command created (§6.3). */
    Optional<BankStatement> findByRequestId(UUID requestId);
}
