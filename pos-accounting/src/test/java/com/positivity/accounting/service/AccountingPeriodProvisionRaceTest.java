package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.AccountingPeriodResponse;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Real-database duplicate-key recovery test for the auto-provision race in
 * {@code AccountingPeriodServiceImpl.findOrProvision} (story B1, issue #937).
 *
 * <p>A pure-mock version of this scenario cannot catch the transactional bug
 * this test guards against: a unique-constraint violation raised inside the
 * caller's transaction marks it rollback-only (and aborts it outright on
 * Postgres), so the race loser's catch-and-re-read commits into an
 * {@code UnexpectedRollbackException}. The fix runs the insert in its own
 * {@code REQUIRES_NEW} transaction ({@code AccountingPeriodProvisioner}).
 *
 * <p>Setup: the winner's row is committed up front, then a repository spy
 * makes the initial {@code findByPeriodCode} existence check miss once, so
 * the service takes the provision path and the real INSERT hits the real
 * {@code uq_accounting_period_code} constraint. This class is deliberately
 * NOT {@code @Transactional}: the service call must run in its own committing
 * transaction so a rollback-only leak would surface as
 * {@code UnexpectedRollbackException}, and the pre-committed winner row must
 * be visible to the inner insert transaction.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("AccountingPeriod auto-provision race (real unique constraint)")
class AccountingPeriodProvisionRaceTest {

    @Autowired
    private AccountingPeriodService periodService;

    @MockitoSpyBean
    private AccountingPeriodRepository periodRepository;

    /** A past month guaranteed to have started. */
    private final YearMonth month = YearMonth.now().minusMonths(4);

    @AfterEach
    void deletePeriods() {
        periodRepository.deleteAll();
    }

    @Test
    @DisplayName("ensurePeriodExists - duplicate-key race loser gets the winner's row and still commits")
    void ensurePeriodExists_duplicateKeyRaceLoser_returnsWinnersRowAndCommits() {
        // Winner's row committed before the racing call.
        AccountingPeriod winner = new AccountingPeriod();
        winner.setPeriodCode(month.toString());
        winner.setStartDate(month.atDay(1));
        winner.setEndDate(month.atEndOfMonth());
        winner.setStatus(AccountingPeriodStatus.OPEN);
        UUID winnerId = periodRepository.saveAndFlush(winner).getPeriodId();

        // Lose the race: the initial existence check misses once, the real
        // provisioning INSERT then violates the real unique constraint, and
        // the recovery re-read must find the winner in the database. The spy
        // is interface-backed, so callRealMethod() is unsupported; unstubbed
        // spy calls DO delegate to the real Spring Data proxy, so the re-read
        // delegates through an equivalent unstubbed real query.
        AtomicBoolean firstLookup = new AtomicBoolean(true);
        doAnswer(invocation -> {
                    if (firstLookup.getAndSet(false)) {
                        return Optional.empty();
                    }
                    String code = invocation.getArgument(0);
                    return periodRepository.findAllByOrderByPeriodCodeDesc().stream()
                            .filter(period -> code.equals(period.getPeriodCode()))
                            .findFirst();
                })
                .when(periodRepository)
                .findByPeriodCode(month.toString());

        // Must not throw (in particular no UnexpectedRollbackException from a
        // rollback-only outer transaction) and must return the winner's row.
        AccountingPeriodResponse response = periodService.ensurePeriodExists(month.atDay(12));

        assertThat(response.getPeriodId()).isEqualTo(winnerId);
        assertThat(response.getPeriodCode()).isEqualTo(month.toString());
        assertThat(response.getStatus()).isEqualTo(AccountingPeriodStatus.OPEN);

        // The outer transaction committed and no duplicate row was persisted.
        assertThat(periodRepository.count()).isEqualTo(1);
        assertThat(periodRepository
                        .findByPeriodCode(month.toString())
                        .orElseThrow()
                        .getPeriodId())
                .isEqualTo(winnerId);
    }
}
