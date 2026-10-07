package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.dto.DepositRecordRequest;
import com.positivity.accounting.internal.dto.DepositResponse;
import com.positivity.accounting.internal.dto.DepositReversalRequest;
import com.positivity.accounting.internal.dto.UndepositedSessionsResponse;
import com.positivity.accounting.internal.enums.DepositStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.service.AccountingCalendarZoneResolver;
import com.positivity.accounting.internal.service.DepositService;
import com.positivity.accounting.internal.service.JournalEntryService;
import com.positivity.accounting.internal.service.KafkaFactIngestionRecorder;
import com.positivity.accounting.internal.service.OrderEventsListener;
import com.positivity.accounting.internal.service.RegisterCashMovementPostingService;
import com.positivity.accounting.internal.service.RegisterOverShortPostingService;
import com.positivity.accounting.internal.service.RegisterSessionReplica;
import com.positivity.accounting.internal.service.UndepositedSessionProjection;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.order.RegisterSessionClosedV1.Movement;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-Postgres IT for bank deposits of drawer cash (CAP:550 S18, #2514): the close fact is driven through {@link
 * OrderEventsListener} as Kafka delivers it, so S17 posts the petty expense and the over/short to 1095 and the
 * projection writes the undeposited session in the same transaction; then the deposit is recorded and reversed through
 * {@link DepositService}, with the 1090 / 1095 / bank balances asserted after each step, on fresh tenants provisioned
 * from the accounting template (BANK_DEPOSIT: UNDEPOSITED_FUNDS → 1090, CASH_CLEARING → 1095). Also: a reversal
 * through the generic journal-entry reversal, two clerks depositing one session at once, the period gate and tenant
 * isolation under RLS.
 *
 * <p>Requires Docker.
 */
@DisplayName("Bank deposits of drawer cash (#2514, real Postgres)")
class BankDepositPostgresIT extends PostgresTenancyTestBase {

    private static final UUID LOCATION = UUID.fromString("019a0000-0000-7000-8000-00000000b001");
    private static final String CLERK_GRANTS = "accounting:deposit:create";

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private RegisterOverShortPostingService overShortPostingService;

    @Autowired
    private RegisterCashMovementPostingService movementPostingService;

    @Autowired
    private KafkaFactIngestionRecorder ingestionRecorder;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AccountingCalendarZoneResolver zoneResolver;

    @Autowired
    private RegisterSessionReplica sessionReplica;

    @Autowired
    private UndepositedSessionProjection undepositedSessions;

    @Autowired
    private DepositService deposits;

    @Autowired
    private JournalEntryService journalEntries;

    private final List<UUID> tenants = new ArrayList<>();

    private OrderEventsListener listener;
    private Instant closedAt;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        listener = new OrderEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                overShortPostingService,
                movementPostingService,
                ingestionRecorder,
                meterRegistry,
                transactionManager,
                zoneResolver,
                sessionReplica,
                undepositedSessions);
        closedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).minus(2, ChronoUnit.HOURS);
        today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC); // the tenant's zone is UTC
    }

    @AfterEach
    void removeTestTenants() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        List<String> scoped = owner.queryForList(
                "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND column_name = 'tenant_id' AND table_name NOT LIKE 'pg_%' ORDER BY table_name",
                String.class);
        for (UUID tenant : tenants) {
            for (int pass = 0; pass < 8; pass++) {
                boolean blocked = false;
                for (String table : scoped) {
                    try {
                        owner.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenant);
                    } catch (RuntimeException stillReferenced) {
                        blocked = true;
                    }
                }
                if (!blocked) {
                    break;
                }
            }
        }
        tenants.clear();
    }

    @Test
    @DisplayName("AC1, AC2, AC4, AC5: close fact → postings → deposit → replay → reversal, 1090 / 1095 / bank after"
            + " each step; the generic journal-entry reversal of a deposit has the same effect")
    void depositLifecycle() {
        UUID tenant = tenant();
        UUID session = UUIDv7Generator.generate();
        closeSession(tenant, workedExample(session));
        // S17: petty Cr 1095 40.00, short Cr 1095 3.00; nothing in 1090 from this fact.
        assertThat(net(tenant, "1095")).isEqualByComparingTo("-43.00");
        assertThat(net(tenant, "1090")).isEqualByComparingTo("0");

        // AC1: the read with the session selected.
        signIn("clerk.ann", CLERK_GRANTS);
        UUID bank = provisionedAccountId(tenant, "1000");
        UndepositedSessionsResponse read = asTenant(tenant, () -> deposits.undeposited(List.of(session), bank));
        assertThat(read.sessions()).singleElement().satisfies(view -> {
            assertThat(view.sessionId()).isEqualTo(session);
            assertThat(view.drops())
                    .extracting(UndepositedSessionsResponse.Drop::bagNumber)
                    .containsExactly("B-0912");
            assertThat(view.locationId()).isEqualTo(LOCATION);
        });
        assertThat(read.selection().depositAmount()).isEqualByComparingTo("1197.00");
        assertThat(read.selection().expectedCash()).isEqualByComparingTo("1240.00");
        assertThat(read.selection().clearingNet()).isEqualByComparingTo("-43.00");
        assertThat(read.selection().difference()).isEqualByComparingTo("0");

        // AC2: recorded into 1000, dated depositDate.
        UUID requestId = UUIDv7Generator.generate();
        DepositRecordRequest request =
                new DepositRecordRequest(bank, today, "USD", List.of(session), requestId, "DS-1", null);
        DepositService.Outcome recorded = asTenant(tenant, () -> deposits.record(request));
        DepositResponse deposit = recorded.response();
        assertThat(recorded.replayed()).isFalse();
        assertThat(deposit.status()).isEqualTo(DepositStatus.RECORDED);
        assertThat(deposit.journalEntryNumber()).startsWith("JE-");
        assertThat(deposit.sessions())
                .singleElement()
                .satisfies(s -> assertThat(s.bagNumbers()).containsExactly("B-0912"));
        List<Map<String, Object>> entry = entryLines(tenant, deposit.journalEntryId());
        assertThat(entry)
                .extracting(line ->
                        line.get("account_code") + " " + line.get("debit_amount") + " " + line.get("credit_amount"))
                .containsExactlyInAnyOrder("1000 1197.0000 0.0000", "1090 0.0000 1240.0000", "1095 43.0000 0.0000");
        assertThat(entry).allSatisfy(line -> {
            assertThat(line.get("source_event_type")).isEqualTo("BANK_DEPOSIT");
            assertThat(((java.sql.Timestamp) line.get("transaction_date")).toLocalDateTime())
                    .isEqualTo(today.atStartOfDay());
        });
        assertThat(net(tenant, "1095")).isEqualByComparingTo("0");
        assertThat(net(tenant, "1090")).isEqualByComparingTo("-1240.00");
        assertThat(net(tenant, "1000")).isEqualByComparingTo("1197.00");
        assertThat(status(tenant, session)).isEqualTo("DEPOSITED");
        assertThat(asTenant(tenant, () -> deposits.undeposited(List.of(), null)).sessions())
                .isEmpty();

        // AC4: the same requestId again is the first result; one deposit, one entry.
        DepositService.Outcome replay = asTenant(tenant, () -> deposits.record(request));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response().depositId()).isEqualTo(deposit.depositId());
        assertThat(count(tenant, "SELECT count(*) FROM deposit WHERE tenant_id = ?"))
                .isEqualTo(1);
        assertThat(count(
                        tenant,
                        "SELECT count(*) FROM journal_entry WHERE tenant_id = ? AND source_event_type ="
                                + " 'BANK_DEPOSIT'"))
                .isEqualTo(1);

        // AC8 (service side): the clerk holds no accounting:deposit:reverse; the endpoint refuses it (controller
        // test). AC5: a CONTROLLER reverses; 1090 and 1095 are restored and the session waits again.
        signIn("controller.cfo", CLERK_GRANTS, "accounting:deposit:reverse");
        DepositReversalRequest reversal = new DepositReversalRequest(
                "Deposited into the wrong bank account", null, null, UUIDv7Generator.generate());
        DepositResponse reversed = asTenant(tenant, () -> deposits.reverse(deposit.depositId(), reversal))
                .response();
        assertThat(reversed.status()).isEqualTo(DepositStatus.REVERSED);
        assertThat(reversed.reversalJournalEntryNumber()).startsWith("JE-");
        assertThat(reversed.reversedBy()).isEqualTo("controller.cfo");
        assertThat(net(tenant, "1095")).isEqualByComparingTo("-43.00");
        assertThat(net(tenant, "1090")).isEqualByComparingTo("0");
        assertThat(net(tenant, "1000")).isEqualByComparingTo("0");
        assertThat(status(tenant, session)).isEqualTo("UNDEPOSITED");
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> deposits.reverse(
                                deposit.depositId(),
                                new DepositReversalRequest(
                                        "Deposited into the wrong bank account",
                                        null,
                                        null,
                                        UUIDv7Generator.generate()))))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.DEPOSIT_ALREADY_REVERSED);
        assertThat(asTenant(tenant, () -> deposits.reverse(deposit.depositId(), reversal))
                        .replayed())
                .isTrue();

        // Recorded again, then reversed as a journal entry: the deposit and the session follow the ledger.
        DepositResponse again = asTenant(
                        tenant,
                        () -> deposits.record(new DepositRecordRequest(
                                bank, today, "USD", List.of(session), UUIDv7Generator.generate(), "DS-2", null)))
                .response();
        assertThat(status(tenant, session)).isEqualTo("DEPOSITED");
        asTenant(tenant, () -> journalEntries.reverseJournalEntry(again.journalEntryId(), "Wrong deposit slip", null));
        DepositResponse generic = asTenant(tenant, () -> deposits.get(again.depositId()));
        assertThat(generic.status()).isEqualTo(DepositStatus.REVERSED);
        assertThat(generic.reversalReason()).isEqualTo("Wrong deposit slip");
        assertThat(status(tenant, session)).isEqualTo("UNDEPOSITED");
        assertThat(net(tenant, "1095")).isEqualByComparingTo("-43.00");
        assertThat(net(tenant, "1090")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("AC3: drops of 1,190.00 are 422 DEPOSIT_UNBALANCED naming 7.00 and nothing posts; a schema-1 fact and"
            + " a session closed in another currency write no undeposited session")
    void unbalancedAndIneligibleSessions() {
        UUID tenant = tenant();
        UUID unbalanced = UUIDv7Generator.generate();
        closeSession(tenant, fact(unbalanced, "-3.00", "USD", "1240.00", petty("40.00"), drop("1190.00", "B-0913")));
        UUID schemaOne = UUIDv7Generator.generate();
        asTenant(
                tenant,
                () -> listener.onOrderEvent(envelope(UUID.randomUUID().toString(), 1, workedExample(schemaOne))));
        UUID foreign = UUIDv7Generator.generate();
        closeSession(tenant, fact(foreign, "0.00", "CAD", "100.00", drop("100.00", "B-CAD")));
        int entries = count(tenant, "SELECT count(*) FROM journal_entry WHERE tenant_id = ?");

        signIn("clerk.ann", CLERK_GRANTS);
        UUID bank = provisionedAccountId(tenant, "1000");
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> deposits.record(new DepositRecordRequest(
                                bank, today, "USD", List.of(unbalanced), UUIDv7Generator.generate(), null, null))))
                .isInstanceOfSatisfying(CashSetupException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(CashSetupException.Code.DEPOSIT_UNBALANCED);
                    assertThat(e.getMessage()).contains("7.00");
                });
        assertThat(count(tenant, "SELECT count(*) FROM journal_entry WHERE tenant_id = ?"))
                .isEqualTo(entries);
        assertThat(status(tenant, unbalanced)).isEqualTo("UNDEPOSITED");
        assertThat(asTenant(tenant, () -> deposits.undeposited(List.of(), null)).sessions())
                .extracting(UndepositedSessionsResponse.Session::sessionId)
                .containsExactly(unbalanced);
    }

    @Test
    @DisplayName("two clerks depositing the same session at once: one deposit, the other 409"
            + " DEPOSIT_SESSION_ALREADY_DEPOSITED")
    void concurrentDepositsOfOneSession() throws Exception {
        UUID tenant = tenant();
        UUID session = UUIDv7Generator.generate();
        closeSession(tenant, workedExample(session));
        UUID bank = provisionedAccountId(tenant, "1000");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> outcomes = new ArrayList<>();
        try {
            List<Future<Object>> racers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                racers.add(pool.submit(race(
                        start,
                        tenant,
                        () -> deposits.record(new DepositRecordRequest(
                                bank, today, "USD", List.of(session), UUIDv7Generator.generate(), null, null)))));
            }
            start.countDown();
            for (Future<Object> racer : racers) {
                outcomes.add(racer.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes)
                .filteredOn(DepositService.Outcome.class::isInstance)
                .hasSize(1);
        assertThat(outcomes)
                .filteredOn(CashSetupException.class::isInstance)
                .singleElement()
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.DEPOSIT_SESSION_ALREADY_DEPOSITED);
        assertThat(count(tenant, "SELECT count(*) FROM deposit WHERE tenant_id = ?"))
                .isEqualTo(1);
        assertThat(net(tenant, "1000")).isEqualByComparingTo("1197.00");
    }

    @Test
    @DisplayName("AC6: a deposit date in a CLOSED period is 422 PERIOD_CLOSED without override, posts with"
            + " accounting:period:override and a justification; a hard-locked date is 422 PERIOD_HARD_LOCKED")
    void depositDatePassesThePeriodGate() {
        UUID tenant = tenant();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.update(
                "INSERT INTO accounting_period (tenant_id, period_id, period_code, start_date, end_date, status,"
                        + " created_at, created_by, modified_at, modified_by, version) VALUES (?, ?, '2026-08',"
                        + " DATE '2026-08-01', DATE '2026-08-31', 'CLOSED', TIMESTAMPTZ '2026-09-01 00:00:00+00',"
                        + " 't', TIMESTAMPTZ '2026-09-01 00:00:00+00', 't', 0)",
                tenant,
                UUIDv7Generator.generate());
        UUID first = UUIDv7Generator.generate();
        UUID second = UUIDv7Generator.generate();
        closeSession(tenant, workedExample(first));
        closeSession(tenant, workedExample(second));
        UUID bank = provisionedAccountId(tenant, "1000");
        LocalDate august = LocalDate.of(2026, 8, 15);

        signIn("clerk.ann", CLERK_GRANTS);
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> deposits.record(new DepositRecordRequest(
                                bank, august, "USD", List.of(first), UUIDv7Generator.generate(), null, null))))
                .isInstanceOf(AccountingPeriodClosedException.class);
        assertThat(status(tenant, first)).isEqualTo("UNDEPOSITED");

        signIn("controller.cfo", CLERK_GRANTS, "accounting:period:override");
        DepositResponse overridden = asTenant(
                        tenant,
                        () -> deposits.record(new DepositRecordRequest(
                                bank,
                                august,
                                "USD",
                                List.of(first),
                                UUIDv7Generator.generate(),
                                null,
                                "Deposit slip found after the month was closed")))
                .response();
        assertThat(overridden.depositDate()).isEqualTo(august);
        assertThat(status(tenant, first)).isEqualTo("DEPOSITED");

        owner.update(
                "INSERT INTO accounting_configuration (tenant_id, config_id, config_key, config_value, created_at,"
                        + " created_by, modified_at, modified_by) VALUES (?, ?, 'HARD_LOCK_DATE', '2026-09-01',"
                        + " TIMESTAMPTZ '2026-09-02 00:00:00+00', 'test', TIMESTAMPTZ '2026-09-02 00:00:00+00', 'test')",
                tenant,
                UUIDv7Generator.generate());
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> deposits.record(new DepositRecordRequest(
                                bank,
                                LocalDate.of(2026, 8, 20),
                                "USD",
                                List.of(second),
                                UUIDv7Generator.generate(),
                                null,
                                "Deposit slip found after the month was closed"))))
                .isInstanceOf(AccountingPeriodHardLockedException.class);
        assertThat(status(tenant, second)).isEqualTo("UNDEPOSITED");
    }

    @Test
    @DisplayName("AC9: a second tenant sees none of the first tenant's sessions or deposits (ADR-0062)")
    void tenantIsolation() {
        UUID tenantA = tenant();
        UUID tenantB = tenant();
        UUID session = UUIDv7Generator.generate();
        closeSession(tenantA, workedExample(session));
        signIn("clerk.ann", CLERK_GRANTS);
        UUID bankA = provisionedAccountId(tenantA, "1000");
        UUID bankB = provisionedAccountId(tenantB, "1000");
        DepositResponse deposit = asTenant(
                        tenantA,
                        () -> deposits.record(new DepositRecordRequest(
                                bankA, today, "USD", List.of(session), UUIDv7Generator.generate(), null, null)))
                .response();
        asTenant(tenantA, () -> journalEntries.reverseJournalEntry(deposit.journalEntryId(), "Isolation check", null));

        assertThat(asTenant(tenantB, () -> deposits.undeposited(List.of(), null))
                        .sessions())
                .isEmpty();
        assertThatThrownBy(() -> asTenant(tenantB, () -> deposits.get(deposit.depositId())))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.DEPOSIT_NOT_FOUND);
        assertThatThrownBy(() -> asTenant(
                        tenantB,
                        () -> deposits.record(new DepositRecordRequest(
                                bankB, today, "USD", List.of(session), UUIDv7Generator.generate(), null, null))))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThat(asTenant(tenantA, () -> deposits.undeposited(List.of(), null))
                        .sessions())
                .hasSize(1);
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private Callable<Object> race(CountDownLatch start, UUID tenant, Callable<Object> work) {
        return () -> {
            start.await();
            signIn("clerk.ann", CLERK_GRANTS);
            try {
                return asTenant(tenant, () -> {
                    try {
                        return work.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            } catch (CashSetupException e) {
                return e;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private void closeSession(UUID tenant, RegisterSessionClosedV1 fact) {
        asTenant(tenant, () -> listener.onOrderEvent(envelope(UUID.randomUUID().toString(), 2, fact)));
    }

    /** AC1: cash sales 1,240.00, petty 40.00, a short of 3.00 and a bank drop of 1,197.00 in bag B-0912. */
    private RegisterSessionClosedV1 workedExample(UUID session) {
        return fact(session, "-3.00", "USD", "1240.00", petty("40.00"), drop("1197.00", "B-0912"));
    }

    private Movement petty(String amount) {
        return new Movement(
                UUIDv7Generator.generate(),
                "PETTY_EXPENSE",
                "OUT",
                new BigDecimal(amount),
                "USD",
                "SHOP_SUPPLIES",
                null,
                null,
                "R-1042",
                "clerk-1",
                null,
                null,
                closedAt.minusSeconds(3600));
    }

    private Movement drop(String amount, String bag) {
        return new Movement(
                UUIDv7Generator.generate(),
                "BANK_DROP",
                "OUT",
                new BigDecimal(amount),
                "USD",
                null,
                null,
                bag,
                null,
                "clerk-1",
                null,
                null,
                closedAt.minusSeconds(600));
    }

    private RegisterSessionClosedV1 fact(
            UUID session, String overShort, String currency, String cashSales, Movement... movements) {
        return new RegisterSessionClosedV1(
                session,
                "T-7",
                LOCATION,
                "clerk-1",
                "clerk-2",
                new BigDecimal("200.00"),
                new BigDecimal("200.00"),
                new BigDecimal("200.00").subtract(new BigDecimal(overShort)),
                new BigDecimal(overShort),
                false,
                currency,
                List.of(
                        new RegisterSessionClosedV1.TenderTotal("CASH", new BigDecimal(cashSales)),
                        new RegisterSessionClosedV1.TenderTotal("CARD", new BigDecimal("812.50"))),
                BigDecimal.ZERO,
                closedAt.minusSeconds(28_800),
                closedAt,
                Arrays.asList(movements));
    }

    private String envelope(String eventId, int schemaVersion, RegisterSessionClosedV1 fact) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":%d,"aggregateId":"%s","aggregateVersion":2,
                 "occurredAtUtc":"%s","sourceService":"pos-order","payload":%s}
                """.formatted(
                        eventId,
                        RegisterSessionClosedV1.EVENT_TYPE,
                        schemaVersion,
                        fact.sessionId(),
                        fact.closedAt(),
                        objectMapper.writeValueAsString(fact));
    }

    private UUID tenant() {
        UUID tenant = tenantWithZone();
        tenants.add(tenant);
        provisionAccounting(tenant);
        return tenant;
    }

    /** Debits minus credits on one account, every posted and reversing entry included. */
    private static BigDecimal net(UUID tenant, String accountCode) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT COALESCE(SUM(l.debit_amount - l.credit_amount), 0) FROM journal_entry_line l"
                                + " JOIN gl_account a ON a.gl_account_id = l.gl_account_id"
                                + " WHERE l.tenant_id = ? AND a.account_code = ?",
                        BigDecimal.class,
                        tenant,
                        accountCode);
    }

    private static List<Map<String, Object>> entryLines(UUID tenant, UUID entry) {
        return new JdbcTemplate(ownerDataSource())
                .queryForList(
                        "SELECT a.account_code, l.debit_amount, l.credit_amount, e.source_event_type,"
                                + " e.transaction_date FROM journal_entry_line l"
                                + " JOIN journal_entry e ON e.journal_entry_id = l.journal_entry_id"
                                + " JOIN gl_account a ON a.gl_account_id = l.gl_account_id"
                                + " WHERE l.tenant_id = ? AND e.journal_entry_id = ?",
                        tenant,
                        entry);
    }

    private static String status(UUID tenant, UUID session) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT status FROM undeposited_session WHERE tenant_id = ? AND session_id = ?",
                        String.class,
                        tenant,
                        session);
    }

    private static int count(UUID tenant, String sql) {
        return new JdbcTemplate(ownerDataSource()).queryForObject(sql, Integer.class, tenant);
    }

    private static void signIn(String username, String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }
}
