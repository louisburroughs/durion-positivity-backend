package com.positivity.accounting.internal.bankrec.repository;

import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@link BankStatement} headers (SPEC §3.1; stories S1 #2300, S2 #2301). The list
 * filters use specifications rather than a nullable-parameter JPQL query (issues #1891, #1961).
 */
public interface BankStatementRepository
        extends JpaRepository<BankStatement, UUID>, JpaSpecificationExecutor<BankStatement> {

    /** U1: the statement already holding exactly this window on the account. */
    Optional<BankStatement> findFirstByGlAccountIdAndStatusAndStartDateAndEndDate(
            @NonNull UUID glAccountId,
            @NonNull BankStatementStatus status,
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate);

    /**
     * U2: the earliest statement whose window overlaps {@code [startDate, endDate]} — one that starts
     * on or before {@code endDate} and ends on or after {@code startDate}.
     */
    Optional<BankStatement>
            findFirstByGlAccountIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAsc(
                    @NonNull UUID glAccountId,
                    @NonNull BankStatementStatus status,
                    @NonNull LocalDate endDate,
                    @NonNull LocalDate startDate);

    /** E2: the statement a new window starting at {@code startDate} would continue. */
    Optional<BankStatement> findFirstByGlAccountIdAndStatusAndEndDateLessThanOrderByEndDateDesc(
            @NonNull UUID glAccountId, @NonNull BankStatementStatus status, @NonNull LocalDate startDate);

    /** The baseline (§3.1): the latest-starting statement that carries a gap acknowledgement. */
    Optional<BankStatement> findFirstByGlAccountIdAndStatusAndGapAcknowledgementIsNotNullOrderByStartDateDesc(
            @NonNull UUID glAccountId, @NonNull BankStatementStatus status);

    /**
     * The baseline of a window (§3.1, §3.7; S4, #2303): the latest acknowledged statement that starts on or
     * before the window start.
     */
    Optional<BankStatement>
            findFirstByGlAccountIdAndStatusAndGapAcknowledgementIsNotNullAndStartDateLessThanEqualOrderByStartDateDesc(
                    @NonNull UUID glAccountId, @NonNull BankStatementStatus status, @NonNull LocalDate onOrBefore);

    /** The coverage frontier (§4.1): the latest-ending statement. */
    Optional<BankStatement> findFirstByGlAccountIdAndStatusOrderByEndDateDesc(
            @NonNull UUID glAccountId, @NonNull BankStatementStatus status);

    /** The statement a manual-statement command created (§6.3). */
    Optional<BankStatement> findByRequestId(@NonNull UUID requestId);

    /** The latest end date of a {@code status} statement per account (coverage frontier, §4.1). */
    @Query(
            "SELECT new com.positivity.accounting.internal.bankrec.repository.AccountDate(s.glAccountId, MAX(s.endDate)) FROM BankStatement s"
                    + " WHERE s.glAccountId IN :ids AND s.status = :status GROUP BY s.glAccountId")
    @NonNull
    List<AccountDate> findLatestEndDateByGlAccountIdIn(
            @Param("ids") @NonNull Collection<UUID> glAccountIds, @Param("status") @NonNull BankStatementStatus status);
}
