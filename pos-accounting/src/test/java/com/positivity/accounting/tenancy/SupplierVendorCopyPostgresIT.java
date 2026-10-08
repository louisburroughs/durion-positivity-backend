package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.ApVendorSettings;
import com.positivity.accounting.internal.repository.ApVendorSettingsRepository;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.SupplierInvoiceHoldRepository;
import com.positivity.accounting.internal.repository.VendorBillReissueRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.KafkaFactIngestionRecorder;
import com.positivity.accounting.internal.service.SupplierEventsListener;
import com.positivity.accounting.internal.service.VendorBillDuplicateGuard;
import com.positivity.accounting.internal.service.VendorBillLocks;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * The vendor copy on Postgres (CAP:550 S24, #2517): AC 15's table scan and log capture, row-level security of the new
 * tables, the {@code ap_vendor} drop, and the consumer's transaction shape (ADR-0044 amendments). Requires Docker.
 */
@DisplayName("Supplier vendor copy on Postgres (S24)")
class SupplierVendorCopyPostgresIT extends PostgresTenancyTestBase {

    /** AC 15's fake registration number, separated and not. Compared against, never printed. */
    private static final String FAKE_NUMBER = "000-00-1234";

    private static final String FAKE_NUMBER_BARE = "000001234";
    private static final String LAST4 = "Z9Q8";

    /** Built by hand: the {@code pg} profile runs without the Kafka rails, so the consumer is not a bean. */
    private SupplierEventsListener listener;

    @Autowired
    private ExtSupplierVendorRepository vendorCopy;

    @Autowired
    private ApVendorSettingsRepository settings;

    @Autowired
    private SupplierInvoiceHoldRepository holds;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProcessedEventRepository processed;

    @Autowired
    private VendorBillRepository bills;

    @Autowired
    private LedgerCurrency ledgerCurrency;

    @Autowired
    private KafkaFactIngestionRecorder ingestion;

    @Autowired
    private VendorBillDuplicateGuard duplicateGuard;

    @Autowired
    private VendorBillReissueRepository reissues;

    @Autowired
    private VendorBillLocks locks;

    @Autowired
    private ObjectProvider<MeterRegistry> meters;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> tenants = new ArrayList<>();
    private ListAppender<ILoggingEvent> logs;
    private Logger accountingLogger;
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        listener = new SupplierEventsListener(
                clock,
                objectMapper,
                processed,
                bills,
                vendorCopy,
                holds,
                ledgerCurrency,
                ingestion,
                duplicateGuard,
                reissues,
                locks,
                meters,
                transactionManager);
        accountingLogger = (Logger) LoggerFactory.getLogger("com.positivity.accounting");
        previousLevel = accountingLogger.getLevel();
        accountingLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        accountingLogger.addAppender(logs);
    }

    @AfterEach
    void cleanUp() {
        accountingLogger.detachAppender(logs);
        accountingLogger.setLevel(previousLevel);
        TenantContext.clear();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        for (UUID tenant : tenants) {
            for (String table : List.of("supplier_invoice_hold", "ap_vendor_settings", "ext_supplier_vendor")) {
                owner.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenant);
            }
        }
        tenants.clear();
    }

    private UUID tenant() {
        UUID tenant = UUIDv7Generator.generate();
        tenants.add(tenant);
        return tenant;
    }

    private static String vendorFact(String eventId, UUID vendorId, int schemaVersion, String registration) {
        return """
            {"eventId":"%s","eventType":"supplier.vendor.updated","schemaVersion":%d,"aggregateVersion":1,
             "aggregateId":"%s","payload":{
              "vendorId":"%s","vendorNumber":"V-000777","legalName":"Acme Parts LLC","displayName":"Acme Parts",
              "taxRegistrations":[%s],"remitTo":null,"remitToVersion":0,"defaultPaymentTerms":"NET30",
              "defaultCurrency":"USD","status":"ACTIVE","createdBy":"u.creator",
              "createdAt":"2026-10-01T00:00:00Z","occurredAt":"2026-10-08T08:00:00Z"}}
            """.formatted(eventId, schemaVersion, vendorId, vendorId, registration);
    }

    private long count(String sql, Object... args) {
        Long n = new JdbcTemplate(ownerDataSource()).queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    /** Every text-like column of every public table, as {@code table.column}. */
    private List<String[]> textColumns() {
        return new JdbcTemplate(ownerDataSource())
                .query(
                        "SELECT c.table_name, c.column_name FROM information_schema.columns c"
                                + " JOIN information_schema.tables t ON t.table_schema = c.table_schema"
                                + " AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'"
                                + " WHERE c.table_schema = 'public'"
                                + " AND c.data_type IN ('text', 'character varying', 'character', 'json', 'jsonb')",
                        (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
    }

    private long rowsCarrying(String value) {
        long found = 0;
        for (String[] column : textColumns()) {
            found += count(
                    "SELECT count(*) FROM public.\"" + column[0] + "\" WHERE \"" + column[1] + "\"::text LIKE ?",
                    "%" + value + "%");
        }
        return found;
    }

    private long logLinesCarrying(String... values) {
        return logs.list.stream()
                .map(event -> event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                                ? ""
                                : event.getThrowableProxy().getMessage()))
                .filter(line -> List.of(values).stream().anyMatch(line::contains))
                .count();
    }

    @Test
    @DisplayName("AC 15: a schemaVersion 1 fact leaves no full number in any table or log line, and is marked")
    void v1FactLeavesNoNumberAnywhere() {
        UUID tenant = tenant();
        UUID vendorId = UUIDv7Generator.generate();
        String eventId = UUIDv7Generator.generate().toString();

        asTenant(
                tenant,
                () -> listener.onSupplierEvent(vendorFact(
                        eventId,
                        vendorId,
                        1,
                        "{\"scheme\":\"SSN\",\"number\":\"" + FAKE_NUMBER + "\",\"region\":null}")));

        assertThat(count("SELECT count(*) FROM ext_supplier_vendor WHERE vendor_id = ?", vendorId))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM processed_events WHERE event_id = ? AND owner = 'supplier'"
                                + " AND tenant_id = ?",
                        eventId,
                        tenant))
                .isEqualTo(1);
        assertThat(rowsCarrying(FAKE_NUMBER))
                .as("rows carrying the separated number")
                .isZero();
        assertThat(rowsCarrying(FAKE_NUMBER_BARE))
                .as("rows carrying the unseparated number")
                .isZero();
        assertThat(logLinesCarrying(FAKE_NUMBER, FAKE_NUMBER_BARE))
                .as("log lines carrying it")
                .isZero();
    }

    @Test
    @DisplayName("AC 15: a schemaVersion 2 fact stores {scheme, region, last4}; last4 is never logged")
    void v2FactStoresLast4Only() {
        UUID tenant = tenant();
        UUID vendorId = UUIDv7Generator.generate();

        asTenant(
                tenant,
                () -> listener.onSupplierEvent(vendorFact(
                        UUIDv7Generator.generate().toString(),
                        vendorId,
                        2,
                        "{\"scheme\":\"EIN\",\"region\":null,\"last4\":\"" + LAST4 + "\"}")));

        Map<String, Object> row = new JdbcTemplate(ownerDataSource())
                .queryForMap(
                        "SELECT tax_registrations->0->>'last4' AS last4, tax_registrations->0->>'scheme' AS scheme,"
                                + " (tax_registrations->0->>'number') IS NOT NULL AS has_number, status FROM ext_supplier_vendor"
                                + " WHERE vendor_id = ?",
                        vendorId);
        assertThat(row.get("last4")).isEqualTo(LAST4);
        assertThat(row.get("scheme")).isEqualTo("EIN");
        assertThat(row.get("has_number")).isEqualTo(false);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(logLinesCarrying(LAST4)).as("log lines carrying last4").isZero();
    }

    @Test
    @DisplayName("the new tables are tenant-isolated by row-level security")
    void rowLevelSecurity() {
        UUID tenantA = tenant();
        UUID tenantB = tenant();
        UUID vendorId = UUIDv7Generator.generate();
        asTenant(
                tenantA,
                () -> listener.onSupplierEvent(
                        vendorFact(UUIDv7Generator.generate().toString(), vendorId, 2, "")));
        asTenant(tenantA, () -> {
            ApVendorSettings row = new ApVendorSettings();
            row.setVendorId(vendorId);
            return settings.save(row);
        });
        // An invoice for a vendor tenant A has not copied: held in tenant A.
        UUID unknownVendor = UUIDv7Generator.generate();
        asTenant(
                tenantA,
                () -> listener.onSupplierEvent(
                        """
                {"eventId":"%s","eventType":"supplier.invoice.received","payload":{
                  "vendorProfileId":"%s","supplierRef":"acme","vendorInvoiceNumber":"INV-9","invoiceDate":"2026-10-06",
                  "type":"INVOICE","currency":"USD","totalNetAmount":100.00,"totalTaxAmount":0,
                  "totalGrossAmount":100.00,"occurredAt":"2026-10-08T08:00:00Z","lines":[],"vendorId":"%s"}}
                """.formatted(UUIDv7Generator.generate(), UUIDv7Generator.generate(), unknownVendor)));

        assertThat(asTenant(tenantA, () -> vendorCopy.findById(vendorId))).isPresent();
        assertThat(asTenant(tenantA, () -> settings.findByVendorId(vendorId))).isPresent();
        assertThat(asTenant(tenantA, () -> holds.count())).isEqualTo(1);

        assertThat(asTenant(tenantB, () -> vendorCopy.findById(vendorId))).isEmpty();
        assertThat(asTenant(tenantB, () -> settings.findByVendorId(vendorId))).isEmpty();
        assertThat(asTenant(tenantB, () -> holds.count())).isZero();
    }

    @Test
    @DisplayName("ap_vendor is gone (V20)")
    void apVendorDropped() {
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject("SELECT to_regclass('public.ap_vendor') IS NULL", Boolean.class))
                .isTrue();
    }

    @Test
    @DisplayName("transaction shape: a database failure in the vendor branch rolls back and propagates, unmarked")
    void databaseFailurePropagatesUnmarked() {
        UUID tenant = tenant();
        UUID vendorId = UUIDv7Generator.generate();
        String eventId = UUIDv7Generator.generate().toString();
        ExtSupplierVendorRepository failing =
                mock(ExtSupplierVendorRepository.class, AdditionalAnswers.delegatesTo(vendorCopy));
        doThrow(new DataAccessResourceFailureException("connection lost"))
                .when(failing)
                .saveAndFlush(any());
        SupplierEventsListener shaped = new SupplierEventsListener(
                clock,
                objectMapper,
                processed,
                bills,
                failing,
                holds,
                ledgerCurrency,
                ingestion,
                duplicateGuard,
                reissues,
                locks,
                meters,
                transactionManager);

        assertThatThrownBy(() -> TenantTestSupport.asTenant(
                        tenant, () -> shaped.onSupplierEvent(vendorFact(eventId, vendorId, 2, ""))))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = ?", eventId))
                .isZero();
        assertThat(count("SELECT count(*) FROM ext_supplier_vendor WHERE vendor_id = ?", vendorId))
                .isZero();
    }
}
