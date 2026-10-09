package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryDeactivateRequest;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.service.AccountingCalendarZoneResolver;
import com.positivity.accounting.internal.service.FactPostingOutcome;
import com.positivity.accounting.internal.service.KafkaFactIngestionRecorder;
import com.positivity.accounting.internal.service.OrderEventsListener;
import com.positivity.accounting.internal.service.PettyExpenseCategoryService;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-Postgres IT for posting a closed register session's drawer movements (CAP:550 S17, #2513), driven through
 * {@link OrderEventsListener} exactly as Kafka delivers {@code order.session.closed}, on fresh tenants provisioned from
 * the accounting template, so the S15 mappings ({@code REGISTER_CASH_MOVEMENT}: {@code PETTY_EXPENSE_*} and {@code
 * CASH_CLEARING} → 1095) resolve end to end. The Kafka rails stay off in tests; the message path from envelope JSON to
 * posting is the listener's own.
 *
 * <p>Requires Docker.
 */
@DisplayName("Drawer movements post to the ledger (#2513, real Postgres)")
class RegisterCashMovementPostingPostgresIT extends PostgresTenancyTestBase {

    private static final UUID LOCATION = UUID.fromString("019a0000-0000-7000-8000-00000000b001");

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
    private PettyExpenseCategoryService categories;

    /** pos-tax's CA profile answers CAD: the fixture a USD tenant's CA registration is checked against (S32d D1). */
    @MockitoBean
    private TaxProfileClient taxProfiles;

    private final List<UUID> tenants = new ArrayList<>();

    private OrderEventsListener listener;
    private Instant closedAt;

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
    @DisplayName("AC1, AC3, AC4: two petty expenses post Dr 6340 / Cr 1095 and Dr 6295 / Cr 1095, dated closedAt,"
            + " dimensioned, no 2200 line; drops and float changes post nothing; a redelivery posts nothing twice")
    void pettyExpensesPostOncePerMovement() {
        UUID tenant = tenant();
        UUID session = UUIDv7Generator.generate();
        RegisterSessionClosedV1 fact = fact(
                session,
                "0.00",
                "USD",
                petty("SHOP_SUPPLIES", "18.40", "R-1042"),
                petty("STAFF_MEALS", "22.00", null),
                other("BANK_DROP", "OUT", "300.00"),
                other("FLOAT_INCREASE", "IN", "50.00"),
                other("FLOAT_DECREASE", "OUT", "20.00"));
        String first = UUID.randomUUID().toString();

        asTenant(tenant, () -> listener.onOrderEvent(envelope(first, fact)));

        List<Map<String, Object>> lines = lines(tenant);
        assertThat(lines).hasSize(4).allSatisfy(line -> {
            assertThat(line.get("source_event_type")).isEqualTo("REGISTER_CASH_MOVEMENT");
            assertThat(line.get("status")).isEqualTo("POSTED");
            assertThat(((java.sql.Timestamp) line.get("transaction_date")).toLocalDateTime())
                    .isEqualTo(java.time.LocalDateTime.ofInstant(closedAt, java.time.ZoneOffset.UTC));
            assertThat((String) line.get("dimensions"))
                    .contains("\"registerId\": \"T-7\"")
                    .contains("\"sessionId\": \"" + session + "\"")
                    .contains("\"locationId\": \"" + LOCATION + "\"");
        });
        assertThat(net(lines, "6340")).isEqualByComparingTo("18.40");
        assertThat(net(lines, "6295")).isEqualByComparingTo("22.00");
        assertThat(net(lines, "1095")).isEqualByComparingTo("-40.40");
        assertThat(lines).noneMatch(line -> "2200".equals(line.get("account_code")));
        assertThat(entryCount(tenant)).isEqualTo(2);
        // CAP:550 S32d AC 1: a USD tenant receives none of the currency-conditional template data.
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT count(*) FROM gl_account WHERE tenant_id = ? AND account_subtype ="
                                        + " 'TAX_RECOVERABLE'",
                                Integer.class,
                                tenant))
                .isZero();
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT count(*) FROM petty_expense_category_tax_setting WHERE tenant_id = ?",
                                Integer.class,
                                tenant))
                .isZero();

        // The fact's one record links an entry it posted.
        List<Map<String, Object>> records = records(tenant, session);
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.get("status")).isEqualTo("PROCESSED");
            assertThat(record.get("idempotency_outcome")).isEqualTo("NEW");
            assertThat(record.get("journal_entry_id")).isNotNull();
        });

        // AC3: redelivered under a fresh envelope id, and replayed as is: nothing posts twice.
        asTenant(tenant, () -> listener.onOrderEvent(envelope(UUID.randomUUID().toString(), fact)));
        asTenant(tenant, () -> listener.onOrderEvent(envelope(first, fact)));
        assertThat(entryCount(tenant)).isEqualTo(2);
        assertThat(records(tenant, session))
                .hasSize(2)
                .extracting(record -> record.get("idempotency_outcome"))
                .containsExactlyInAnyOrder("NEW", "DUPLICATE_IGNORED");
        assertThat(records(tenant, session))
                .allSatisfy(record -> assertThat(record.get("journal_entry_id")).isNotNull());

        // Past the posting keys' 24 hours (expired, not yet cleaned up), a re-emit under a fresh envelope id still
        // posts nothing twice: the entries' deterministic source events are the backstop, and no key is re-registered.
        new JdbcTemplate(ownerDataSource())
                .update(
                        "UPDATE idempotency_keys SET expires_at = now() - interval '1 day' WHERE tenant_id = ?",
                        tenant);
        asTenant(tenant, () -> listener.onOrderEvent(envelope(UUID.randomUUID().toString(), fact)));
        assertThat(entryCount(tenant)).isEqualTo(2);
        assertThat(records(tenant, session))
                .hasSize(3)
                .extracting(record -> record.get("idempotency_outcome"))
                .containsExactlyInAnyOrder("NEW", "DUPLICATE_IGNORED", "DUPLICATE_IGNORED");

        // Two-tenant isolation (ADR-0062): another tenant sees none of these entries, and the same movements (the
        // same movement ids) delivered to it post its own two entries: the posting keys are tenant-scoped. Its
        // session id differs: the session replica's key is the session id alone (pos-order's UUIDv7 ids never repeat
        // across tenants).
        UUID other = tenant();
        assertThat(entryCount(other)).isZero();
        assertThat(records(other, session)).isEmpty();
        UUID otherSession = UUIDv7Generator.generate();
        RegisterSessionClosedV1 sameMovements =
                fact(otherSession, "0.00", "USD", fact.movements().toArray(Movement[]::new));
        asTenant(other, () -> listener.onOrderEvent(envelope(UUID.randomUUID().toString(), sameMovements)));
        assertThat(entryCount(other)).isEqualTo(2);
        assertThat(net(lines(other), "1095")).isEqualByComparingTo("-40.40");
        assertThat(records(other, otherSession))
                .singleElement()
                .satisfies(
                        record -> assertThat(record.get("idempotency_outcome")).isEqualTo("NEW"));
        assertThat(entryCount(tenant)).isEqualTo(2);
    }

    @Test
    @DisplayName("All or nothing: an over/short that fails after the movements posted rolls the movement entries back"
            + " and leaves the fact unmarked")
    void failingOverShortRollsTheMovementsBack() {
        UUID tenant = tenant();
        String eventId = UUID.randomUUID().toString();
        OrderEventsListener failing = new OrderEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                new FailingOverShortPostingService(),
                movementPostingService,
                ingestionRecorder,
                meterRegistry,
                transactionManager,
                zoneResolver,
                sessionReplica,
                undepositedSessions);
        RegisterSessionClosedV1 fact = fact(
                UUIDv7Generator.generate(),
                "-3.00",
                "USD",
                petty("SHOP_SUPPLIES", "18.40", "R-9"),
                petty("STAFF_MEALS", "22.00", null));

        assertThatThrownBy(() -> asTenant(tenant, () -> failing.onOrderEvent(envelope(eventId, fact))))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated PERIOD_CLOSED");

        assertThat(entryCount(tenant)).isZero();
        assertThat(records(tenant, fact.sessionId())).isEmpty();
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT count(*) FROM idempotency_keys WHERE tenant_id = ?", Integer.class, tenant))
                .isZero();
        assertThat(asTenant(tenant, () -> processedEventRepository.existsById(eventId)))
                .isFalse();

        // The retry, once the over/short posts, posts the movements: none of their keys was kept.
        asTenant(tenant, () -> listener.onOrderEvent(envelope(eventId, fact)));
        assertThat(entryCount(tenant)).isEqualTo(3);
        assertThat(net(lines(tenant), "1095")).isEqualByComparingTo("-43.40");
    }

    @Test
    @DisplayName("AC5 (non-COD): petty 40.00 and a short of 3.00 leave 1095 at a net credit of 43.00; a vendor cash on"
            + " delivery movement is held, not posted, until its AP payment posting exists (#2576)")
    void clearingHoldsTheSessionNet() {
        UUID tenant = tenant();
        UUID session = UUIDv7Generator.generate();
        RegisterSessionClosedV1 fact = fact(
                session,
                "-3.00",
                "USD",
                petty("SHOP_SUPPLIES", "40.00", "R-1"),
                new Movement(
                        UUIDv7Generator.generate(),
                        "VENDOR_COD",
                        "OUT",
                        new BigDecimal("145.00"),
                        "USD",
                        null,
                        UUIDv7Generator.generate(),
                        null,
                        null,
                        "clerk-1",
                        null,
                        null,
                        closedAt.minusSeconds(600),
                        null,
                        List.of(),
                        null,
                        null,
                        null));

        asTenant(tenant, () -> listener.onOrderEvent(envelope(UUID.randomUUID().toString(), fact)));

        List<Map<String, Object>> lines = lines(tenant);
        assertThat(net(lines, "1095")).isEqualByComparingTo("-43.00");
        assertThat(net(lines, "6340")).isEqualByComparingTo("40.00");
        assertThat(net(lines, "6040")).isEqualByComparingTo("3.00");
        assertThat(lines).noneMatch(line -> "2000".equals(line.get("account_code")));
        assertThat(entryCount(tenant)).isEqualTo(2);
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject("SELECT count(*) FROM ap_payment WHERE tenant_id = ?", Integer.class, tenant))
                .isZero();
    }

    @Test
    @DisplayName("AC7: a session in a non-ledger currency posts nothing, over/short included, and is held once as"
            + " SUSPENDED / CURRENCY_NOT_SUPPORTED")
    void foreignSessionIsHeldOnce() {
        UUID tenant = tenant();
        UUID session = UUIDv7Generator.generate();
        RegisterSessionClosedV1 fact = fact(session, "-3.00", "EUR", petty("SHOP_SUPPLIES", "18.40", null, "EUR"));

        asTenant(tenant, () -> listener.onOrderEvent(envelope(UUID.randomUUID().toString(), fact)));
        asTenant(tenant, () -> listener.onOrderEvent(envelope(UUID.randomUUID().toString(), fact)));

        assertThat(entryCount(tenant)).isZero();
        assertThat(records(tenant, session)).singleElement().satisfies(record -> {
            assertThat(record.get("status")).isEqualTo("SUSPENDED");
            assertThat(record.get("failure_reason_code")).isEqualTo("CURRENCY_NOT_SUPPORTED");
            assertThat((String) record.get("error_message")).contains("EUR");
        });
    }

    @Test
    @DisplayName("AC8: a schema-1 fact posts only its over/short, as before")
    void schemaOneFactPostsTheOverShortOnly() {
        UUID tenant = tenant();
        UUID session = UUIDv7Generator.generate();
        RegisterSessionClosedV1 fact = new RegisterSessionClosedV1(
                session,
                "T-7",
                LOCATION,
                "clerk-1",
                "clerk-2",
                new BigDecimal("200.00"),
                new BigDecimal("190.00"),
                new BigDecimal("200.00"),
                new BigDecimal("-10.00"),
                false,
                "USD",
                List.of(),
                BigDecimal.ZERO,
                closedAt.minusSeconds(28_800),
                closedAt,
                null);

        asTenant(tenant, () -> listener.onOrderEvent(envelope(UUID.randomUUID().toString(), fact)));

        List<Map<String, Object>> lines = lines(tenant);
        assertThat(lines)
                .allSatisfy(line -> assertThat(line.get("source_event_type")).isEqualTo("REGISTER_OVER_SHORT"));
        assertThat(net(lines, "6040")).isEqualByComparingTo("10.00");
        assertThat(net(lines, "1095")).isEqualByComparingTo("-10.00");
    }

    @Test
    @DisplayName("All or nothing: a movement whose mapping is missing rolls back the session's other postings and"
            + " leaves the fact unmarked for retry / DLQ; a deactivated category still resolves")
    void sessionPostsAllOrNothing() {
        UUID tenant = tenant();
        String eventId = UUID.randomUUID().toString();
        RegisterSessionClosedV1 broken = fact(
                UUIDv7Generator.generate(),
                "-3.00",
                "USD",
                petty("SHOP_SUPPLIES", "18.40", null),
                petty("NOT_A_CATEGORY", "5.00", null));

        assertThatThrownBy(() -> asTenant(tenant, () -> listener.onOrderEvent(envelope(eventId, broken))))
                .isInstanceOf(GLMappingNotConfiguredException.class);
        assertThat(entryCount(tenant)).isZero();
        assertThat(asTenant(tenant, () -> processedEventRepository.existsById(eventId)))
                .isFalse();

        // A category deactivated after its movement was recorded still posts (its mapping key stays).
        signIn("controller.cfo");
        asTenant(
                tenant,
                () -> categories.deactivate(
                        "VEHICLE_FUEL",
                        new PettyExpenseCategoryDeactivateRequest(
                                "We stopped buying fuel in cash", UUIDv7Generator.generate())));
        asTenant(
                tenant,
                () -> listener.onOrderEvent(envelope(
                        UUID.randomUUID().toString(),
                        fact(UUIDv7Generator.generate(), "0.00", "USD", petty("VEHICLE_FUEL", "31.00", null)))));
        List<Map<String, Object>> lines = lines(tenant);
        assertThat(net(lines, "6250")).isEqualByComparingTo("31.00");
        assertThat(net(lines, "1095")).isEqualByComparingTo("-31.00");
    }

    @Test
    @DisplayName("CAP:550 S32d AC 1 [M] (D1): a USD tenant holding an in-effect CA registration recovers nothing;"
            + " the currency guard, not a missing registration, withholds it")
    void usdTenantWithCaRegistrationRecoversNothing() {
        UUID tenant = tenant();
        org.mockito.Mockito.when(taxProfiles.taxTypes("CA"))
                .thenReturn(new TaxProfileClient.TaxTypes("CA", "CAD", List.of(), List.of()));
        new JdbcTemplate(ownerDataSource())
                .update(
                        "INSERT INTO ext_tax_registration (tenant_id, registration_id, country_code, regime,"
                                + " registration_number, jurisdiction_code, effective_from, aggregate_version,"
                                + " changed_at, synced_at) VALUES (?, ?, 'CA', 'GST_HST', '123456789RT0001', 'CA',"
                                + " DATE '2020-01-01', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00',"
                                + " TIMESTAMPTZ '2026-01-01 00:00:00+00')",
                        tenant,
                        UUIDv7Generator.generate());
        Movement stated = new Movement(
                UUIDv7Generator.generate(),
                "PETTY_EXPENSE",
                "OUT",
                new BigDecimal("40.00"),
                "USD",
                "SHOP_SUPPLIES",
                null,
                null,
                "R-D1",
                "clerk-1",
                null,
                null,
                closedAt.minusSeconds(3600),
                "Corner Hardware",
                List.of(new RegisterSessionClosedV1.StatedTax("GST_HST", new BigDecimal("4.60"))),
                "123456789RT0001",
                Movement.PLAUSIBLE,
                Boolean.FALSE);

        asTenant(
                tenant,
                () -> listener.onOrderEvent(envelope(
                        UUID.randomUUID().toString(), fact(UUIDv7Generator.generate(), "0.00", "USD", stated))));

        // The registration is in effect, so only the currency guard can withhold: CA's currency is not USD's.
        org.mockito.Mockito.verify(taxProfiles, org.mockito.Mockito.atLeastOnce())
                .taxTypes("CA");
        List<Map<String, Object>> lines = lines(tenant);
        assertThat(net(lines, "6340")).isEqualByComparingTo("40.00");
        assertThat(net(lines, "1095")).isEqualByComparingTo("-40.00");
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT recovery_withheld_reason FROM register_cash_movement_tax_recovery"
                                        + " WHERE tenant_id = ? AND movement_id = ?",
                                String.class,
                                tenant,
                                stated.movementId()))
                .isEqualTo("NOT_REGISTERED");
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    /** An over/short posting that fails the way a closed period does, after the movements posted. */
    private static final class FailingOverShortPostingService extends RegisterOverShortPostingService {

        FailingOverShortPostingService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public @NonNull FactPostingOutcome postOverShort(
                @NonNull RegisterSessionClosedV1 fact, @NonNull String envelopeEventId) {
            throw new IllegalStateException("simulated PERIOD_CLOSED");
        }
    }

    private Movement petty(String category, String amount, String receipt) {
        return petty(category, amount, receipt, "USD");
    }

    private Movement petty(String category, String amount, String receipt, String currencyCode) {
        return new Movement(
                UUIDv7Generator.generate(),
                "PETTY_EXPENSE",
                "OUT",
                new BigDecimal(amount),
                currencyCode,
                category,
                null,
                null,
                receipt,
                "clerk-1",
                null,
                null,
                closedAt.minusSeconds(3600),
                null,
                List.of(),
                null,
                null,
                null);
    }

    private Movement other(String reason, String direction, String amount) {
        return new Movement(
                UUIDv7Generator.generate(),
                reason,
                direction,
                new BigDecimal(amount),
                "USD",
                null,
                null,
                "BANK_DROP".equals(reason) ? "BAG-7" : null,
                null,
                "clerk-1",
                null,
                UUIDv7Generator.generate(),
                closedAt.minusSeconds(1800),
                null,
                List.of(),
                null,
                null,
                null);
    }

    private RegisterSessionClosedV1 fact(UUID session, String overShort, String currency, Movement... movements) {
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
                List.of(new RegisterSessionClosedV1.TenderTotal("CASH", new BigDecimal("100.00"))),
                BigDecimal.ZERO,
                closedAt.minusSeconds(28_800),
                closedAt,
                Arrays.asList(movements));
    }

    private String envelope(String eventId, RegisterSessionClosedV1 fact) {
        // A fact without movements is a schema-1 fact; schema 2 always carries the list (#2514 rejects it otherwise).
        int schemaVersion = fact.movements() == null ? 1 : 2;
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

    private static List<Map<String, Object>> lines(UUID tenant) {
        return new JdbcTemplate(ownerDataSource())
                .queryForList(
                        "SELECT a.account_code, l.debit_amount, l.credit_amount, l.dimensions::text AS dimensions,"
                                + " e.source_event_type, e.status, e.transaction_date FROM journal_entry_line l"
                                + " JOIN journal_entry e ON e.journal_entry_id = l.journal_entry_id"
                                + " JOIN gl_account a ON a.gl_account_id = l.gl_account_id WHERE l.tenant_id = ?",
                        tenant);
    }

    /** Debits minus credits on one account. */
    private static BigDecimal net(List<Map<String, Object>> lines, String accountCode) {
        return lines.stream()
                .filter(line -> accountCode.equals(line.get("account_code")))
                .map(line -> ((BigDecimal) line.get("debit_amount")).subtract((BigDecimal) line.get("credit_amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static int entryCount(UUID tenant) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject("SELECT count(*) FROM journal_entry WHERE tenant_id = ?", Integer.class, tenant);
    }

    private static List<Map<String, Object>> records(UUID tenant, UUID session) {
        return new JdbcTemplate(ownerDataSource())
                .queryForList(
                        "SELECT status, idempotency_outcome, journal_entry_id, failure_reason_code, error_message"
                                + " FROM accounting_event WHERE tenant_id = ? AND event_type = ? AND domain_key_id = ?",
                        tenant,
                        RegisterSessionClosedV1.EVENT_TYPE,
                        session.toString());
    }

    private static void signIn(String username) {
        UsernamePasswordAuthenticationToken caller =
                new UsernamePasswordAuthenticationToken(username, "n/a", List.of());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }
}
