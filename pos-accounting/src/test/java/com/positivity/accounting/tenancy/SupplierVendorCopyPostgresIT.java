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
import com.positivity.accounting.internal.service.VendorBillStatedTax;
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
    private VendorBillStatedTax statedTax;

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
                statedTax,
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
            for (String table : List.of(
                    "accounting_event",
                    "supplier_invoice_hold",
                    "vendor_bill",
                    "ap_vendor_settings",
                    "ext_supplier_vendor")) {
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

        // Positive control of AC 15's scan: it reads the copy's registrations column and finds a value stored there,
        // so its zero for the full number means something.
        assertThat(textColumns())
                .anySatisfy(column -> assertThat(column).containsExactly("ext_supplier_vendor", "tax_registrations"));
        assertThat(rowsCarrying(LAST4)).as("the scan finds the stored last4").isPositive();
    }

    // ---- item 1: nothing reaches a log line or an exception through the JDBC error path -------------------------

    private static final String PAYEE_MARKER = "PAYEE-MARKER-7Q";

    /** Every message of an exception's cause chain. */
    private static List<String> chainOf(Throwable thrown) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
            for (Throwable suppressed : cause.getSuppressed()) {
                messages.add(String.valueOf(suppressed.getMessage()));
            }
        }
        return messages;
    }

    /** Every formatted line and every message of every logged throwable's cause chain, from ROOT. */
    private static long rootLinesCarrying(ListAppender<ILoggingEvent> captured, String... values) {
        return captured.list.stream()
                .flatMap(event -> {
                    List<String> lines = new ArrayList<>();
                    lines.add(event.getFormattedMessage());
                    for (ch.qos.logback.classic.spi.IThrowableProxy proxy = event.getThrowableProxy();
                            proxy != null;
                            proxy = proxy.getCause()) {
                        lines.add(String.valueOf(proxy.getMessage()));
                    }
                    return lines.stream();
                })
                .filter(line -> List.of(values).stream().anyMatch(line::contains))
                .count();
    }

    private ListAppender<ILoggingEvent> captureRoot(List<Runnable> restore) {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        root.setLevel(Level.DEBUG);
        restore.add(() -> {
            root.detachAppender(captured);
            root.setLevel(before);
        });
        return captured;
    }

    @Test
    @DisplayName("item 1: a v2 fact without a displayName leaks neither last4 nor the remit-to into a log or exception")
    void unreadableVendorFactLeaksNothing() {
        UUID tenant = tenant();
        UUID vendorId = UUIDv7Generator.generate();
        String fact = vendorFact(
                        UUIDv7Generator.generate().toString(),
                        vendorId,
                        2,
                        "{\"scheme\":\"EIN\",\"region\":null,\"last4\":\"" + LAST4 + "\"}")
                .replace("\"displayName\":\"Acme Parts\"", "\"displayName\":null")
                .replace(
                        "\"remitTo\":null",
                        "\"remitTo\":{\"payeeName\":\"" + PAYEE_MARKER + "\",\"addressLine1\":\"1 Main St\","
                                + "\"city\":\"Springfield\",\"region\":\"ST\",\"postalCode\":\"00001\","
                                + "\"countryCode\":\"US\"}");
        List<Runnable> restore = new ArrayList<>();
        ListAppender<ILoggingEvent> root = captureRoot(restore);
        Throwable thrown = null;
        try {
            asTenant(tenant, () -> listener.onSupplierEvent(fact));
        } catch (RuntimeException e) {
            thrown = e;
        } finally {
            restore.forEach(Runnable::run);
        }

        assertThat(count("SELECT count(*) FROM ext_supplier_vendor WHERE vendor_id = ?", vendorId))
                .isZero();
        assertThat(rootLinesCarrying(root, LAST4, PAYEE_MARKER))
                .as("ROOT lines carrying either")
                .isZero();
        if (thrown != null) {
            assertThat(chainOf(thrown)).noneMatch(m -> m.contains(LAST4) || m.contains(PAYEE_MARKER));
        }
    }

    @Test
    @DisplayName("item 1: a copy row the database refuses (status CHECK) carries no row detail into logs or exceptions"
            + " (pgjdbc logServerErrorDetail=false)")
    void refusedCopyRowLeaksNoFailingRow() {
        UUID tenant = tenant();
        com.positivity.accounting.internal.entity.ExtSupplierVendor row =
                new com.positivity.accounting.internal.entity.ExtSupplierVendor();
        row.setVendorId(UUIDv7Generator.generate());
        row.setVendorNumber("V-LEAK-1");
        row.setDisplayName(PAYEE_MARKER);
        // Refused by ext_supplier_vendor_status_check: Postgres would quote the whole failing row in its DETAIL.
        row.setStatus("BOGUS");
        row.setCreatedBy("u.creator");
        row.setAggregateVersion(1);
        row.setTaxRegistrations(List.of(new java.util.LinkedHashMap<>(Map.of("scheme", "EIN", "last4", LAST4))));
        List<Runnable> restore = new ArrayList<>();
        ListAppender<ILoggingEvent> root = captureRoot(restore);
        Throwable thrown;
        try {
            thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> asTenant(tenant, () -> vendorCopy.saveAndFlush(row)));
        } finally {
            restore.forEach(Runnable::run);
        }

        assertThat(thrown).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(chainOf(thrown)).noneMatch(m -> m.contains(LAST4) || m.contains(PAYEE_MARKER));
        // last4 in no line at all (Hibernate's entity listing is pinned to INFO); the driver's detail in none either.
        // The display name is CONFIDENTIAL for a sole proprietor (ADR-0072 D1): DEBUG may carry it (Spring Data's
        // "Touched <entity>"), INFO and above may not; the JDBC error path (WARN / ERROR) is where "Failing row
        // contains (...)" would carry it.
        assertThat(rootLinesCarrying(root, LAST4, "Failing row"))
                .as("ROOT lines carrying last4 or a failing row")
                .isZero();
        assertThat(root.list.stream()
                        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                        .filter(event -> event.getFormattedMessage().contains(PAYEE_MARKER)))
                .as("WARN/ERROR lines carrying the refused row's values")
                .isEmpty();
    }

    @Autowired
    private javax.sql.DataSource dataSource;

    @Test
    @DisplayName("ADR-0072: the application pool's driver keeps the server's DETAIL out of exception messages")
    void driverKeepsServerDetailOutOfMessages() {
        // On a row-level-security table Postgres never sends a non-owner role the failing row, so the vendor copy is
        // safe whatever the driver does; logServerErrorDetail=false covers every other table and connection. A unique
        // violation on the global processed_events table carries "Key (event_id)=(...)" in its DETAIL: proof the
        // pool's driver drops it.
        String marker = "DETAIL-" + UUIDv7Generator.generate().toString().substring(0, 20);
        JdbcTemplate app = new JdbcTemplate(dataSource);
        String insert = "INSERT INTO processed_events (event_id, owner, processed_at) VALUES (?, 'it', now())";
        try {
            app.update(insert, marker);
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> app.update(insert, marker));
            assertThat(thrown).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            assertThat(chainOf(thrown)).noneMatch(m -> m.contains(marker));
        } finally {
            new JdbcTemplate(ownerDataSource()).update("DELETE FROM processed_events WHERE event_id = ?", marker);
        }
    }

    // ---- item 6: AC 4 on Postgres --------------------------------------------------------------------------------

    @Test
    @DisplayName("AC 4: an invoice held before its vendor is copied becomes one bill when the vendor arrives, the hold"
            + " RELEASED with the bill, one ingestion record")
    void heldInvoiceIsReleasedWhenTheVendorArrives() {
        UUID tenant = tenant();
        UUID vendorId = UUIDv7Generator.generate();
        UUID invoiceEvent = UUIDv7Generator.generate();
        asTenant(
                tenant,
                () -> listener.onSupplierEvent("""
                {"eventId":"%s","eventType":"supplier.invoice.received","payload":{
                  "vendorProfileId":"%s","supplierRef":"acme","vendorInvoiceNumber":"INV-AC4","invoiceDate":"2026-10-06",
                  "type":"INVOICE","currency":"USD","totalNetAmount":100.00,"totalTaxAmount":0,
                  "totalGrossAmount":100.00,"occurredAt":"2026-10-08T08:00:00Z","lines":[],"vendorId":"%s"}}
                """.formatted(invoiceEvent, UUIDv7Generator.generate(), vendorId)));
        assertThat(count("SELECT count(*) FROM vendor_bill WHERE vendor_id = ?", vendorId))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM supplier_invoice_hold WHERE event_id = ? AND reason = 'VENDOR_NOT_IN_COPY'"
                                + " AND released_at IS NULL",
                        invoiceEvent))
                .isEqualTo(1);

        asTenant(
                tenant,
                () -> listener.onSupplierEvent(
                        vendorFact(UUIDv7Generator.generate().toString(), vendorId, 2, "")));

        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        List<UUID> billIds = owner.queryForList(
                "SELECT vendor_bill_id FROM vendor_bill WHERE vendor_id = ? AND tenant_id = ?",
                UUID.class,
                vendorId,
                tenant);
        assertThat(billIds).hasSize(1);
        Map<String, Object> hold = owner.queryForMap(
                "SELECT released_at, released_bill_id FROM supplier_invoice_hold WHERE event_id = ?", invoiceEvent);
        assertThat(hold.get("released_at")).isNotNull();
        assertThat(hold.get("released_bill_id")).isEqualTo(billIds.getFirst());
        assertThat(count("SELECT count(*) FROM accounting_event WHERE ingestion_id = ?", invoiceEvent))
                .isEqualTo(1);
    }

    // ---- LOW: the vendor search's LIKE wildcards are literal -----------------------------------------------------

    @Autowired
    private com.positivity.accounting.internal.service.VendorDirectoryService directory;

    @Test
    @DisplayName("the vendor search matches % and _ literally")
    void searchWildcardsAreLiteral() {
        UUID tenant = tenant();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        for (String name : List.of("100% Parts", "100X Parts", "1_0 Tools", "1X0 Tools")) {
            owner.update(
                    "INSERT INTO ext_supplier_vendor (tenant_id, vendor_id, vendor_number, display_name, status,"
                            + " remit_to_version, tax_registrations, created_by, aggregate_version, updated_at)"
                            + " VALUES (?, ?, ?, ?, 'ACTIVE', 0, '[]'::jsonb, 'it', 1, now())",
                    tenant,
                    UUIDv7Generator.generate(),
                    "V-" + name.replace(" ", ""),
                    name);
        }

        assertThat(asTenant(tenant, () -> directory.searchVendors("100%", null, 20)))
                .extracting(com.positivity.accounting.internal.dto.VendorResponse::getName)
                .containsExactly("100% Parts");
        assertThat(asTenant(tenant, () -> directory.searchVendors("1_0", null, 20)))
                .extracting(com.positivity.accounting.internal.dto.VendorResponse::getName)
                .containsExactly("1_0 Tools");
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
    @DisplayName("#2615: the hold and information-return columns are tenant-isolated, and their CHECKs refuse a hold"
            + " without a reason and a reportable vendor without a form")
    void holdAndInformationReturnColumns() {
        UUID tenantA = tenant();
        UUID tenantB = tenant();
        UUID vendorId = UUIDv7Generator.generate();
        asTenant(tenantA, () -> {
            ApVendorSettings row = new ApVendorSettings();
            row.setVendorId(vendorId);
            row.setApHold(true);
            row.setApHoldReason("Disputed delivery 4471, awaiting credit");
            row.setApHoldSetBy("q.controller");
            row.setApHoldSetAt(clock.instant());
            row.setInformationReturnReportable(true);
            row.setInformationReturnForm("ZZ_FORM_A");
            row.setInformationReturnBox("1");
            row.setInformationReturnPayeeScheme("ZZ_BUSINESS_ID");
            return settings.save(row);
        });

        assertThat(asTenant(tenantA, () -> settings.findByVendorIdIn(List.of(vendorId))))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.isApHold()).isTrue();
                    assertThat(row.getApHoldReason()).isEqualTo("Disputed delivery 4471, awaiting credit");
                    assertThat(row.getInformationReturnForm()).isEqualTo("ZZ_FORM_A");
                });
        assertThat(asTenant(tenantB, () -> settings.findByVendorIdIn(List.of(vendorId))))
                .isEmpty();

        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        String insert = "INSERT INTO ap_vendor_settings (tenant_id, ap_vendor_settings_id, vendor_id, version,"
                + " created_at, updated_at, ap_hold, ap_hold_reason, ap_hold_set_by, ap_hold_set_at,"
                + " information_return_reportable, information_return_form, information_return_box)"
                + " VALUES (?, ?, ?, 0, now(), now(), ?, ?, ?, ?, ?, ?, ?)";
        java.sql.Timestamp now = java.sql.Timestamp.from(clock.instant());
        assertThatThrownBy(() -> owner.update(
                        insert, tenantB, UUIDv7Generator.generate(), vendorId, true, null, "q", now, false, null, null))
                .as("a hold without a reason")
                .hasMessageContaining("ap_vendor_settings_hold_check");
        assertThatThrownBy(() -> owner.update(
                        insert,
                        tenantB,
                        UUIDv7Generator.generate(),
                        vendorId,
                        true,
                        " short   ",
                        "q",
                        now,
                        false,
                        null,
                        null))
                .as("a hold with a reason under 10 characters once trimmed")
                .hasMessageContaining("ap_vendor_settings_hold_check");
        assertThatThrownBy(() -> owner.update(
                        insert,
                        tenantB,
                        UUIDv7Generator.generate(),
                        vendorId,
                        false,
                        "A stale reason left",
                        null,
                        null,
                        false,
                        null,
                        null))
                .as("a reason without a hold")
                .hasMessageContaining("ap_vendor_settings_hold_check");
        assertThatThrownBy(() -> owner.update(
                        insert,
                        tenantB,
                        UUIDv7Generator.generate(),
                        vendorId,
                        false,
                        null,
                        null,
                        null,
                        true,
                        null,
                        "1"))
                .as("reportable without a form")
                .hasMessageContaining("ap_vendor_settings_information_return_check");
        assertThatThrownBy(() -> owner.update(
                        insert,
                        tenantB,
                        UUIDv7Generator.generate(),
                        vendorId,
                        false,
                        null,
                        null,
                        null,
                        false,
                        "ZZ_FORM_A",
                        null))
                .as("a form without reportable")
                .hasMessageContaining("ap_vendor_settings_information_return_check");
        assertThat(count("SELECT count(*) FROM ap_vendor_settings WHERE tenant_id = ?", tenantB))
                .isZero();
    }

    @Test
    @DisplayName(
            "paymentDetailsChanged counts only open bills: a fully paid bill approved at an older version does not")
    void paymentDetailsChangedCountsOpenBillsOnly() {
        UUID tenant = tenant();
        UUID vendorId = UUIDv7Generator.generate();
        asTenant(
                tenant,
                () -> listener.onSupplierEvent(
                        vendorFact(UUIDv7Generator.generate().toString(), vendorId, 2, "")
                                .replace("\"remitToVersion\":0", "\"remitToVersion\":2")));
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        UUID paid = approvedBill(owner, tenant, vendorId, "S24-PAID-1", "100.00", 1);
        UUID payment = UUIDv7Generator.generate();
        owner.update(
                "INSERT INTO ap_payment (tenant_id, payment_id, vendor_id, currency, gross_amount, status, created_at,"
                        + " created_by, payment_ref) VALUES (?, ?, ?, 'USD', 100.00, 'GL_POSTED', now(), 'it', ?)",
                tenant,
                payment,
                vendorId,
                "S24-PAY-" + payment);
        owner.update(
                "INSERT INTO ap_payment_allocation (tenant_id, allocation_id, payment_id, vendor_bill_id,"
                        + " applied_amount, created_at) VALUES (?, ?, ?, ?, 100.00, now())",
                tenant,
                UUIDv7Generator.generate(),
                payment,
                paid);

        assertThat(asTenant(
                        tenant,
                        () -> bills.findVendorIdsWithBillsApprovedAtAnotherRemitTo(
                                List.of(vendorId), com.positivity.accounting.internal.enums.VendorBillStatus.APPROVED)))
                .as("a fully paid bill approved at version 1")
                .isEmpty();

        approvedBill(owner, tenant, vendorId, "S24-OPEN-1", "50.00", 1);
        assertThat(asTenant(
                        tenant,
                        () -> bills.findVendorIdsWithBillsApprovedAtAnotherRemitTo(
                                List.of(vendorId), com.positivity.accounting.internal.enums.VendorBillStatus.APPROVED)))
                .as("an open bill approved at version 1")
                .containsExactly(vendorId);
        owner.update("DELETE FROM ap_payment_allocation WHERE tenant_id = ?", tenant);
        owner.update("DELETE FROM ap_payment WHERE tenant_id = ?", tenant);
        owner.update("DELETE FROM vendor_bill WHERE tenant_id = ?", tenant);
    }

    private static UUID approvedBill(
            JdbcTemplate owner, UUID tenant, UUID vendorId, String number, String total, int approvedAt) {
        UUID billId = UUIDv7Generator.generate();
        owner.update(
                "INSERT INTO vendor_bill (tenant_id, vendor_bill_id, vendor_id, bill_number, bill_number_key, status,"
                        + " total_amount, bill_date, created_at, modified_at, created_by, modified_by, approved_at,"
                        + " approved_by, approved_remit_to_version) VALUES (?, ?, ?, ?, ?, 'APPROVED', ?::numeric,"
                        + " TIMESTAMP '2026-10-01 00:00:00', now(), now(), 'it', 'it', now(), 'it', ?)",
                tenant,
                billId,
                vendorId,
                number,
                number.replace("-", ""),
                total,
                approvedAt);
        return billId;
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
                statedTax,
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
