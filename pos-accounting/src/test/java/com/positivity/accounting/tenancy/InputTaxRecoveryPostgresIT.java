package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.service.AccountingCalendarZoneResolver;
import com.positivity.accounting.internal.service.KafkaFactIngestionRecorder;
import com.positivity.accounting.internal.service.OrderEventsListener;
import com.positivity.accounting.internal.service.RegisterCashMovementPostingService;
import com.positivity.accounting.internal.service.RegisterOverShortPostingService;
import com.positivity.accounting.internal.service.RegisterSessionReplica;
import com.positivity.accounting.internal.service.UndepositedSessionProjection;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.order.RegisterSessionClosedV1.Movement;
import com.positivity.shared.id.UUIDv7Generator;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-Postgres IT for CAP:550 S32d on a CAD ledger (fixture data: pos-tax's CA profile answers CAD): the CAD
 * template data reaches a tenant provisioned from the template (AC 2), a petty expense whose stated GST_HST is
 * eligible posts its recovery at close (AC 5) once per movement (AC 15), and a tenant without a registration recovers
 * nothing (AC 3). The functional currency is still deployment-wide (ADR-0067 A2 pending), so the USD side of AC 1 runs
 * in the USD-ledger ITs.
 *
 * <p>Requires Docker.
 */
@DisplayName("S32d input-tax recovery on a CAD ledger (real Postgres)")
@TestPropertySource(properties = {"accounting.ledger.base-currency=CAD", "accounting.tax.country=CA"})
class InputTaxRecoveryPostgresIT extends PostgresTenancyTestBase {

    private static final UUID LOCATION = UUID.fromString("019a0000-0000-7000-8000-00000000b032");

    @MockitoBean
    private TaxProfileClient taxProfiles;

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
        when(taxProfiles.taxTypes("CA")).thenReturn(new TaxProfileClient.TaxTypes("CA", "CAD", List.of(), List.of()));
    }

    @Test
    @DisplayName("AC 2: a CAD tenant receives the CAD accounts, keys and category shares (Staff meals at 50 %)")
    void cadProvisioning() {
        UUID tenant = tenant();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

        assertThat(owner.queryForList(
                        "SELECT account_code FROM gl_account WHERE tenant_id = ? AND account_code IN"
                                + " ('1250','1260','2210','2220','2230','6050') ORDER BY account_code",
                        String.class,
                        tenant))
                .containsExactly("1250", "1260", "2210", "2220", "2230", "6050");
        assertThat(owner.queryForObject(
                        "SELECT account_subtype FROM gl_account WHERE tenant_id = ? AND account_code = '1250'",
                        String.class,
                        tenant))
                .isEqualTo("TAX_RECOVERABLE");
        assertThat(owner.queryForList(
                        "SELECT key_name FROM mapping_key WHERE tenant_id = ? AND (key_name LIKE 'INPUT_TAX_%'"
                                + " OR key_name LIKE 'TAX_RECOVERABLE_%' OR key_name LIKE 'SALES_TAX_PAYABLE_%'"
                                + " OR key_name = 'CASH_ROUNDING_DIFFERENCE') ORDER BY key_name",
                        String.class, tenant))
                .containsExactly(
                        "CASH_ROUNDING_DIFFERENCE",
                        "INPUT_TAX_GST_HST",
                        "INPUT_TAX_QST",
                        "SALES_TAX_PAYABLE_GST",
                        "SALES_TAX_PAYABLE_HST",
                        "SALES_TAX_PAYABLE_PST",
                        "SALES_TAX_PAYABLE_QST",
                        "TAX_RECOVERABLE_GST_HST",
                        "TAX_RECOVERABLE_QST");
        List<Map<String, Object>> shares = owner.queryForList(
                "SELECT code, recoverable_percent FROM petty_expense_category_tax_setting WHERE tenant_id = ?"
                        + " ORDER BY code",
                tenant);
        assertThat(shares).hasSize(9);
        assertThat(shares)
                .allSatisfy(row -> assertThat((BigDecimal) row.get("recoverable_percent"))
                        .isEqualByComparingTo("STAFF_MEALS".equals(row.get("code")) ? "50.00" : "100.00"));
    }

    @Test
    @DisplayName("AC 5, AC 15: a 40.00 receipt with GST_HST 4.60 posts Dr 6340 35.40, Dr 1250 4.60, Cr 1095 40.00,"
            + " once per movement")
    void recoveryAtClose() {
        UUID tenant = tenant();
        register(tenant);
        Movement movement = petty("SHOP_SUPPLIES", "40.00", "4.60");
        RegisterSessionClosedV1 fact = fact(movement);

        asTenant(tenant, () -> listener.onOrderEvent(envelope(UUID.randomUUID().toString(), fact)));
        asTenant(tenant, () -> listener.onOrderEvent(envelope(UUID.randomUUID().toString(), fact)));

        List<Map<String, Object>> lines = lines(tenant);
        assertThat(net(lines, "6340")).isEqualByComparingTo("35.40");
        assertThat(net(lines, "1250")).isEqualByComparingTo("4.60");
        assertThat(net(lines, "1095")).isEqualByComparingTo("-40.00");
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForList(
                                "SELECT regime, recovered_amount, recovery_withheld_reason,"
                                        + " supplier_registration_number FROM register_cash_movement_tax_recovery"
                                        + " WHERE tenant_id = ? AND movement_id = ?",
                                tenant,
                                movement.movementId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("regime")).isEqualTo("GST_HST");
                    assertThat((BigDecimal) row.get("recovered_amount")).isEqualByComparingTo("4.60");
                    assertThat(row.get("recovery_withheld_reason")).isNull();
                    assertThat(row.get("supplier_registration_number")).isEqualTo("123456789RT0001");
                });
    }

    @Test
    @DisplayName(
            "AC 3: a CAD tenant without a registration recovers nothing (NOT_REGISTERED); the gross is the expense")
    void noRegistrationRecoversNothing() {
        UUID tenant = tenant();
        Movement movement = petty("SHOP_SUPPLIES", "40.00", "4.60");

        asTenant(tenant, () -> listener.onOrderEvent(envelope(UUID.randomUUID().toString(), fact(movement))));

        List<Map<String, Object>> lines = lines(tenant);
        assertThat(net(lines, "6340")).isEqualByComparingTo("40.00");
        assertThat(net(lines, "1250")).isZero();
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT recovery_withheld_reason FROM register_cash_movement_tax_recovery"
                                        + " WHERE tenant_id = ? AND movement_id = ?",
                                String.class,
                                tenant,
                                movement.movementId()))
                .isEqualTo("NOT_REGISTERED");
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private void register(UUID tenant) {
        new JdbcTemplate(ownerDataSource())
                .update(
                        "INSERT INTO ext_tax_registration (tenant_id, registration_id, country_code, regime,"
                                + " registration_number, jurisdiction_code, effective_from, aggregate_version,"
                                + " changed_at, synced_at) VALUES (?, ?, 'CA', 'GST_HST', '123456789RT0001', 'CA',"
                                + " DATE '2020-01-01', 1, TIMESTAMPTZ '2026-01-01 00:00:00+00',"
                                + " TIMESTAMPTZ '2026-01-01 00:00:00+00')",
                        tenant,
                        UUIDv7Generator.generate());
    }

    private Movement petty(String category, String amount, String gstHst) {
        return new Movement(
                UUIDv7Generator.generate(),
                "PETTY_EXPENSE",
                "OUT",
                new BigDecimal(amount),
                "CAD",
                category,
                null,
                null,
                "R-32",
                "clerk-1",
                null,
                null,
                closedAt.minusSeconds(3600),
                "Corner Hardware",
                List.of(new RegisterSessionClosedV1.StatedTax("GST_HST", new BigDecimal(gstHst))),
                "123456789RT0001",
                Movement.PLAUSIBLE,
                Boolean.FALSE);
    }

    private RegisterSessionClosedV1 fact(Movement... movements) {
        return new RegisterSessionClosedV1(
                UUIDv7Generator.generate(),
                "T-32",
                LOCATION,
                "clerk-1",
                "clerk-2",
                new BigDecimal("200.00"),
                new BigDecimal("200.00"),
                new BigDecimal("200.00"),
                BigDecimal.ZERO,
                false,
                "CAD",
                List.of(new RegisterSessionClosedV1.TenderTotal("CASH", new BigDecimal("100.00"))),
                BigDecimal.ZERO,
                closedAt.minusSeconds(28_800),
                closedAt,
                List.of(movements));
    }

    private String envelope(String eventId, RegisterSessionClosedV1 fact) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":2,"aggregateId":"%s","aggregateVersion":2,
                 "occurredAtUtc":"%s","sourceService":"pos-order","payload":%s}
                """.formatted(
                        eventId,
                        RegisterSessionClosedV1.EVENT_TYPE,
                        fact.sessionId(),
                        fact.closedAt(),
                        objectMapper.writeValueAsString(fact));
    }

    private UUID tenant() {
        UUID tenant = tenantWithZone();
        provisionAccounting(tenant);
        return tenant;
    }

    private static List<Map<String, Object>> lines(UUID tenant) {
        return new JdbcTemplate(ownerDataSource())
                .queryForList(
                        "SELECT a.account_code, l.debit_amount, l.credit_amount FROM journal_entry_line l"
                                + " JOIN gl_account a ON a.gl_account_id = l.gl_account_id WHERE l.tenant_id = ?",
                        tenant);
    }

    private static BigDecimal net(List<Map<String, Object>> lines, String accountCode) {
        return lines.stream()
                .filter(line -> accountCode.equals(line.get("account_code")))
                .map(line -> ((BigDecimal) line.get("debit_amount")).subtract((BigDecimal) line.get("credit_amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
