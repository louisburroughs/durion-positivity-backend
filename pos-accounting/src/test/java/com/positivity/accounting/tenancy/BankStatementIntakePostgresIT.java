package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.bankrec.dto.BankStatementCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.BankStatementResponse;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.FeedChange;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.BankTransactionIntake;
import com.positivity.accounting.internal.bankrec.intake.IntakeContext;
import com.positivity.accounting.internal.bankrec.intake.IntakeResult;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankStatementService;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.BankTransactionObserved;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.Change;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.StatementHeader;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The intake against the real baseline (SPEC-manual-bank-reconciliation §3.1, §3.2, §8.2 [M]; story
 * S2, #2301): U1 and U2 hold even when two commits race past the service checks, U3's partial unique
 * holds, and the reconciliation baseline is set, left, refused, moved and recomputed with exactly one
 * {@code BANK_ACCOUNT_BASELINE_SET} row per change in the commit transaction. Requires Docker.
 */
@DisplayName("Bank statement intake on Postgres (#2301)")
class BankStatementIntakePostgresIT extends PostgresTenancyTestBase {

    private static final String ACTOR = "preparer";
    private static final String ACK = "Account opened at the new bank this month";

    @Autowired
    private BankTransactionIntake intake;

    @Autowired
    private BankStatementService statementService;

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private BankStatementRepository statements;

    @Autowired
    private BankTransactionRepository transactions;

    @Autowired
    private BankAccountProfileRepository profiles;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID account;

    @BeforeEach
    void freshBankAccount() {
        String suffix = UUIDv7Generator.generate().toString().substring(26);
        account = asTenant(
                TENANT_A,
                () -> new TransactionTemplate(transactionManager).execute(status -> {
                    GLAccount cash = new GLAccount();
                    cash.setGlAccountId(UUIDv7Generator.generate());
                    cash.setAccountCode("B" + suffix);
                    cash.setAccountName("Bank " + suffix);
                    cash.setAccountType(AccountType.ASSET);
                    cash.setAccountSubtype(AccountSubtype.BANK_CASH);
                    cash.setReconcilable(true);
                    cash.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
                    cash.setCreatedBy(ACTOR);
                    cash.setModifiedBy(ACTOR);
                    return glAccounts.saveAndFlush(cash).getGlAccountId();
                }));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.update(
                "DELETE FROM accounting_audit_log WHERE entity_id = ? OR entity_id IN"
                        + " (SELECT statement_id FROM bank_statement WHERE gl_account_id = ?)",
                account,
                account);
        owner.update("DELETE FROM bank_transaction WHERE gl_account_id = ?", account);
        owner.update("DELETE FROM bank_statement WHERE gl_account_id = ?", account);
        owner.update("DELETE FROM bank_account_profile WHERE gl_account_id = ?", account);
        owner.update("DELETE FROM gl_account WHERE gl_account_id = ?", account);
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private static BankTransactionsObservedV1 statement(String start, String end, String opening, String closing) {
        LocalDate day = LocalDate.parse(start);
        BigDecimal activity = new BigDecimal(closing).subtract(new BigDecimal(opening));
        return new BankTransactionsObservedV1(
                BankTransactionsObservedV1.SourceKind.MANUAL_ENTRY,
                null,
                null,
                null,
                null,
                "USD",
                Instant.parse("2025-12-31T00:00:00Z"),
                null,
                new StatementHeader(null, day, LocalDate.parse(end), new BigDecimal(opening), new BigDecimal(closing)),
                List.of(new BankTransactionObserved(
                        null,
                        1,
                        Change.ADDED,
                        BankTransactionsObservedV1.SettlementState.POSTED,
                        day,
                        null,
                        activity,
                        null,
                        "ACTIVITY " + start,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));
    }

    private IntakeResult commit(BankTransactionsObservedV1 batch, String acknowledgement) {
        return asTenant(
                TENANT_A,
                () -> new TransactionTemplate(transactionManager)
                        .execute(status ->
                                intake.accept(batch, IntakeContext.of(account, ACTOR, acknowledgement, null))));
    }

    private int baselineRows() {
        Integer count = new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT count(*) FROM accounting_audit_log WHERE entity_type = 'BANK_ACCOUNT_PROFILE'"
                                + " AND entity_id = ? AND operation = 'BANK_ACCOUNT_BASELINE_SET'",
                        Integer.class,
                        account);
        return count == null ? 0 : count;
    }

    private LocalDate baseline() {
        return asTenant(TENANT_A, () -> profiles.findById(account).orElseThrow().getReconciliationBaselineDate());
    }

    private static BankRecErrorCode codeOf(Throwable thrown) {
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof BankRecException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("a BankRecException in %s", thrown).isNotNull();
        return ((BankRecException) cause).code();
    }

    // ---- U1 / U2 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("two overlapping commits racing past the service checks: the exclusion constraint refuses the loser")
    void aRacingOverlapIsRefusedByTheExclusionConstraint() throws Exception {
        assertRaceLoserAnswers("2025-01-15", "2025-02-14", BankRecErrorCode.STATEMENT_PERIOD_OVERLAP);
    }

    @Test
    @DisplayName("two commits of the same window racing: the U1 unique index refuses the loser")
    void aRacingSameWindowIsRefusedByTheUniqueIndex() throws Exception {
        assertRaceLoserAnswers("2025-01-01", "2025-01-31", BankRecErrorCode.STATEMENT_ALREADY_IMPORTED);
    }

    private void assertRaceLoserAnswers(String start, String end, BankRecErrorCode expected) throws Exception {
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IntakeResult> first = pool.submit(() -> asTenant(
                    TENANT_A,
                    () -> new TransactionTemplate(transactionManager).execute(status -> {
                        IntakeResult result = intake.accept(
                                statement("2025-01-01", "2025-01-31", "0", "100"),
                                IntakeContext.of(account, ACTOR, ACK, null));
                        firstWritten.countDown();
                        awaitQuietly(releaseFirst);
                        return result;
                    })));
            assertThat(firstWritten.await(30, TimeUnit.SECONDS)).isTrue();

            // The first commit is written but not committed: the second's service checks cannot see it,
            // so only the database stands between the two windows.
            Future<IntakeResult> second = pool.submit(() -> asTenant(
                    TENANT_A,
                    () -> new TransactionTemplate(transactionManager)
                            .execute(status -> intake.accept(
                                    statement(start, end, "0", "100"), IntakeContext.of(account, ACTOR, ACK, null)))));
            Thread.sleep(1_000);
            releaseFirst.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS).statementId()).isNotNull();
            assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(expected));
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
        assertThat(asTenant(
                        TENANT_A,
                        () -> statements.findAll().stream()
                                .filter(s -> account.equals(s.getGlAccountId()))
                                .count()))
                .isEqualTo(1);
    }

    // ---- request-id race ------------------------------------------------------------------------------

    private BankStatementCreateRequest manualRequest(UUID requestId, String description) {
        return BankStatementCreateRequest.builder()
                .glAccountId(account)
                .requestId(requestId)
                .currency("USD")
                .gapAcknowledgement(ACK)
                .statement(BankStatementCreateRequest.Header.builder()
                        .startDate(LocalDate.parse("2025-01-01"))
                        .endDate(LocalDate.parse("2025-01-31"))
                        .openingBalance(BigDecimal.ZERO)
                        .closingBalance(new BigDecimal("100"))
                        .build())
                .transactions(List.of(BankStatementCreateRequest.Transaction.builder()
                        .date(LocalDate.parse("2025-01-01"))
                        .signedAmount(new BigDecimal("100"))
                        .description(description)
                        .build()))
                .build();
    }

    @Test
    @DisplayName("two identical submissions racing on one requestId: the loser replays the winner")
    void theLoserOfTwoIdenticalSubmissionsReplaysTheWinner() throws Exception {
        UUID requestId = UUIDv7Generator.generate();
        List<BankStatementResponse> answers =
                raceOnRequestId(manualRequest(requestId, "DEPOSIT"), manualRequest(requestId, "DEPOSIT"));

        assertThat(answers.get(0).isReplayed()).isFalse();
        assertThat(answers.get(1).isReplayed()).isTrue();
        assertThat(answers.get(1).getStatementId()).isEqualTo(answers.get(0).getStatementId());
    }

    @Test
    @DisplayName("a different payload racing on one requestId still answers IDEMPOTENCY_CONFLICT")
    void aDifferentPayloadRacingOnOneRequestIdIsAConflict() {
        UUID requestId = UUIDv7Generator.generate();
        assertThatThrownBy(
                        () -> raceOnRequestId(manualRequest(requestId, "DEPOSIT"), manualRequest(requestId, "OTHER")))
                .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(BankRecErrorCode.IDEMPOTENCY_CONFLICT))
                .hasMessageContaining("different payload");
    }

    /**
     * Holds the first submission written but uncommitted while the second passes the request-id lookup
     * and blocks on the database; answers both results in order.
     */
    private List<BankStatementResponse> raceOnRequestId(
            BankStatementCreateRequest winner, BankStatementCreateRequest loser) throws Exception {
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BankStatementResponse> first = pool.submit(() -> asTenant(
                    TENANT_A,
                    () -> new TransactionTemplate(transactionManager).execute(status -> {
                        BankStatementResponse result = statementService.createManualStatement(winner);
                        firstWritten.countDown();
                        awaitQuietly(releaseFirst);
                        return result;
                    })));
            assertThat(firstWritten.await(30, TimeUnit.SECONDS)).isTrue();

            Future<BankStatementResponse> second =
                    pool.submit(() -> asTenant(TENANT_A, () -> statementService.createManualStatement(loser)));
            Thread.sleep(1_000);
            releaseFirst.countDown();

            BankStatementResponse won = first.get(30, TimeUnit.SECONDS);
            BankStatementResponse lost = second.get(30, TimeUnit.SECONDS);
            assertThat(asTenant(
                            TENANT_A,
                            () -> statements.findAll().stream()
                                    .filter(st -> account.equals(st.getGlAccountId()))
                                    .count()))
                    .isEqualTo(1);
            return List.of(won, lost);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("an overlapping window written around the service is refused by the exclusion constraint")
    void theExclusionConstraintHoldsWithoutTheServiceCheck() {
        commit(statement("2025-01-01", "2025-01-31", "0", "100"), ACK);
        assertThatThrownBy(() -> asTenant(
                        TENANT_A,
                        () -> new TransactionTemplate(transactionManager).execute(status -> {
                            BankStatement overlapping = new BankStatement();
                            overlapping.setGlAccountId(account);
                            overlapping.setSourceKind(SourceKind.MANUAL_ENTRY);
                            overlapping.setStartDate(LocalDate.of(2025, 1, 20));
                            overlapping.setEndDate(LocalDate.of(2025, 2, 10));
                            overlapping.setOpeningBalance(BigDecimal.ZERO);
                            overlapping.setClosingBalance(BigDecimal.ZERO);
                            overlapping.setActivityTotal(BigDecimal.ZERO);
                            overlapping.setCurrency("USD");
                            overlapping.setStatus(BankStatementStatus.COMMITTED);
                            return statements.saveAndFlush(overlapping);
                        })))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("bank_statement_no_overlap_ex");
    }

    // ---- U3 ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("U3: the source-id partial unique refuses a second row; the intake upserts instead")
    void theSourceIdIsUnique() {
        UUID importId = UUIDv7Generator.generate();
        BankTransactionObserved t1 = new BankTransactionObserved(
                "T1",
                1,
                Change.ADDED,
                BankTransactionsObservedV1.SettlementState.POSTED,
                LocalDate.of(2025, 3, 3),
                null,
                new BigDecimal("12.50"),
                null,
                "CARD PAYMENT",
                null,
                null,
                null,
                null,
                null,
                null);
        BankTransactionObserved t1Again = new BankTransactionObserved(
                "T1",
                1,
                Change.MODIFIED,
                BankTransactionsObservedV1.SettlementState.POSTED,
                LocalDate.of(2025, 3, 3),
                null,
                new BigDecimal("12.50"),
                null,
                "CARD PAYMENT - CORNER CAFE",
                null,
                null,
                null,
                null,
                null,
                null);
        for (BankTransactionObserved row : List.of(t1, t1Again)) {
            asTenant(
                    TENANT_A,
                    () -> new TransactionTemplate(transactionManager)
                            .execute(status -> intake.accept(
                                    new BankTransactionsObservedV1(
                                            BankTransactionsObservedV1.SourceKind.FILE_IMPORT,
                                            "csv-v1",
                                            null,
                                            null,
                                            null,
                                            "USD",
                                            Instant.parse("2025-03-04T00:00:00Z"),
                                            null,
                                            null,
                                            List.of(row)),
                                    IntakeContext.of(account, ACTOR, null, importId))));
        }
        List<BankTransaction> rows = asTenant(
                TENANT_A,
                () -> transactions.findAll().stream()
                        .filter(t -> account.equals(t.getGlAccountId()))
                        .toList());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getFeedChange()).isEqualTo(FeedChange.MODIFIED);
            assertThat(row.getDescription()).isEqualTo("CARD PAYMENT - CORNER CAFE");
            assertThat(row.getOriginalDescription()).isEqualTo("CARD PAYMENT");
            assertThat(row.getSignedAmount()).isEqualByComparingTo("12.5000");
        });

        assertThatThrownBy(() -> asTenant(
                        TENANT_A,
                        () -> new TransactionTemplate(transactionManager).execute(status -> {
                            BankTransaction duplicate = new BankTransaction();
                            duplicate.setGlAccountId(account);
                            duplicate.setSourceKind(SourceKind.FILE_IMPORT);
                            duplicate.setSourceRef(importId);
                            duplicate.setSourceTransactionId("T1");
                            duplicate.setSettlementState(SettlementState.POSTED);
                            duplicate.setTransactionDate(LocalDate.of(2025, 3, 3));
                            duplicate.setSignedAmount(BigDecimal.ONE);
                            duplicate.setCurrency("USD");
                            duplicate.setStatus(BankTransactionStatus.UNMATCHED);
                            return transactions.saveAndFlush(duplicate);
                        })))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("bank_transaction_source_id_uk");
    }

    // ---- baseline --------------------------------------------------------------------------------------

    @Test
    @DisplayName("baseline: set, unchanged, refused, moved and recomputed — one audit row per change")
    void theBaselineLifecycle() {
        assertThatThrownBy(() -> commit(statement("2025-01-01", "2025-01-31", "0", "100"), null))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.STATEMENT_NOT_CONTIGUOUS));
        assertThat(asTenant(TENANT_A, () -> profiles.findById(account)))
                .as("a refused commit writes nothing")
                .isEmpty();

        // The first statement, acknowledged: profile created with the §3.1 values, baseline set.
        IntakeResult first = commit(statement("2025-01-01", "2025-01-31", "0", "100"), ACK);
        assertThat(first.baselineChanged()).isTrue();
        asTenant(TENANT_A, () -> {
            var profile = profiles.findById(account).orElseThrow();
            assertThat(profile.getBankName()).isNull();
            assertThat(profile.getAccountMask()).isNull();
            assertThat(profile.getCurrency()).isEqualTo("USD");
            assertThat(profile.getReconciliationBaselineDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        });
        assertThat(baselineRows()).isEqualTo(1);

        // A contiguous statement: unchanged, no row.
        commit(statement("2025-02-01", "2025-02-28", "100", "150"), null);
        assertThat(baseline()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(baselineRows()).isEqualTo(1);

        // A contiguous statement with an acknowledgement: refused, unchanged.
        assertThatThrownBy(() -> commit(statement("2025-03-01", "2025-03-31", "150", "175"), ACK))
                .satisfies(t ->
                        assertThat(codeOf(t)).isEqualTo(BankRecErrorCode.STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE));
        assertThat(baseline()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(baselineRows()).isEqualTo(1);

        // After a gap, acknowledged: moved, one more row.
        IntakeResult moved = commit(statement("2025-05-01", "2025-05-31", "900", "1000"), ACK);
        assertThat(baseline()).isEqualTo(LocalDate.of(2025, 5, 1));
        assertThat(baselineRows()).isEqualTo(2);

        // S5 supersedes May: the recompute moves the baseline back to January, one more row.
        new JdbcTemplate(ownerDataSource())
                .update("UPDATE bank_statement SET status = 'SUPERSEDED' WHERE statement_id = ?", moved.statementId());
        java.util.Optional<LocalDate> recomputed = asTenant(
                TENANT_A,
                () -> new TransactionTemplate(transactionManager)
                        .execute(status -> intake.recomputeBaseline(account, ACTOR, "May statement superseded")));
        assertThat(recomputed).contains(LocalDate.of(2025, 1, 1));
        assertThat(baselineRows()).isEqualTo(3);

        // Nothing changed: no row.
        asTenant(
                TENANT_A,
                () -> new TransactionTemplate(transactionManager)
                        .execute(status -> intake.recomputeBaseline(account, ACTOR, "no change expected")));
        assertThat(baselineRows()).isEqualTo(3);
    }
}
