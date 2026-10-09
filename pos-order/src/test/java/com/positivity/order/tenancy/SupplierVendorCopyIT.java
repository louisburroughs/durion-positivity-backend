package com.positivity.order.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.order.internal.entity.ExtSupplierVendor;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtSupplierVendorRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.PurchaseOrderTransmissionEventRepository;
import com.positivity.order.internal.service.SupplierOrderResultListener;
import com.positivity.order.internal.service.SupplierVendorGuard;
import com.positivity.order.internal.service.SupplierVendorReplica;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * pos-order's vendor copy on Postgres (CAP:550 S24, #2517; ADR-0062): the {@code supplier.vendor.updated} fact
 * consumed under tenant A writes A's copy row and A's processed mark (owner {@code supplier}) together, through the
 * listener's own transaction; tenant B sees neither the row (repository and raw SQL, row-level security) nor the
 * vendor through the purchase-order vendor guard, and a version 1 fact writes a mark and no row.
 */
@DisplayName("Vendor copy isolation on Postgres (S24, pos-order)")
class SupplierVendorCopyIT extends PostgresTenancyTestBase {

    @Autowired
    private SupplierVendorReplica vendorReplica;

    @Autowired
    private PurchaseOrderRepository purchaseOrders;

    @Autowired
    private PurchaseOrderTransmissionEventRepository transmissionEvents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ExtSupplierVendorRepository vendors;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private SupplierVendorGuard vendorGuard;

    @Autowired
    private DataSource dataSource;

    private SupplierOrderResultListener listener;

    /**
     * Built here because the {@code pg} profile runs without Kafka, so no {@code @KafkaRails} listener bean exists;
     * every collaborator is the context's own, so the handler transaction and its mark are the production ones.
     */
    @BeforeEach
    void listener() {
        listener = new SupplierOrderResultListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                processedEvents,
                purchaseOrders,
                transmissionEvents,
                vendorReplica,
                transactionManager);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static String vendorFact(UUID eventId, UUID vendorId, int schemaVersion, String registration) {
        return """
            {"eventId":"%s","eventType":"supplier.vendor.updated","schemaVersion":%d,"aggregateId":"%s",
             "aggregateVersion":3,"payload":{
              "vendorId":"%s","vendorNumber":"V-000777","legalName":"Copy Test Ltd","displayName":"Copy Test",
              "taxRegistrations":[%s],"remitTo":null,"remitToVersion":0,"defaultPaymentTerms":"NET30",
              "defaultCurrency":"EUR","status":"ACTIVE","statusChangedAt":null,"statusReason":null,
              "remitToChangedAt":null,"remitToRequestedBy":null,"remitToApprovedBy":null,"createdBy":"alice",
              "createdAt":"2026-10-01T00:00:00Z","occurredAt":"2026-10-07T09:00:00Z"}}
            """.formatted(eventId, schemaVersion, vendorId, vendorId, registration);
    }

    @Test
    void aCopiedVendorIsTheConsumingTenantsAlone() {
        UUID vendorId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        asTenant(
                TENANT_A,
                () -> listener.onSupplierEvent(
                        vendorFact(eventId, vendorId, 2, "{\"scheme\":\"VAT\",\"region\":null,\"last4\":\"Z9Q8\"}")));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(TENANT_A, () -> {
            ExtSupplierVendor row = vendors.findById(vendorId).orElseThrow();
            assertThat(row.getTenantId()).isEqualTo(TENANT_A);
            assertThat(row.getVendorNumber()).isEqualTo("V-000777");
            assertThat(row.getAggregateVersion()).isEqualTo(3L);
            assertThat(vendorGuard.requireActive(vendorId).getVendorId()).isEqualTo(vendorId);
            assertThat(count(jdbc, vendorId)).as("owner reads through raw SQL").isEqualTo(1);
            ProcessedEvent mark = processedEvents.findById(eventId.toString()).orElseThrow();
            assertThat(mark.getOwner()).isEqualTo("supplier");
            assertThat(mark.getTenantId()).isEqualTo(TENANT_A);
        });

        asTenant(TENANT_B, () -> {
            assertThat(vendors.findById(vendorId))
                    .as("Hibernate filter hides it")
                    .isEmpty();
            assertThat(count(jdbc, vendorId)).as("RLS hides it from raw SQL").isZero();
            assertThatThrownBy(() -> vendorGuard.requireActive(vendorId))
                    .as("another tenant's vendor is not in this tenant's copy")
                    .isInstanceOfSatisfying(
                            com.positivity.web.common.ReplicationPendingException.class,
                            e -> assertThat(e.getCode()).isEqualTo("VENDOR_REPLICATION_PENDING"));
        });

        assertThat(count(jdbc, vendorId))
                .as("an unbound connection sees nothing")
                .isZero();
    }

    @Test
    void aVersionOneFactIsMarkedAndCopiesNothing() {
        UUID vendorId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        asTenant(
                TENANT_A,
                () -> listener.onSupplierEvent(vendorFact(
                        eventId, vendorId, 1, "{\"scheme\":\"SSN\",\"number\":\"000-00-1234\",\"region\":null}")));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        asTenant(TENANT_A, () -> {
            assertThat(vendors.findById(vendorId)).isEmpty();
            assertThat(count(jdbc, vendorId)).isZero();
            assertThat(processedEvents
                            .findById(eventId.toString())
                            .orElseThrow()
                            .getOwner())
                    .isEqualTo("supplier");
        });
    }

    /**
     * ADR-0072 and the driver flag ({@code logServerErrorDetail=false}): a copy row the database refuses must not put
     * its column values in any log line or in the propagated exception. A temporary CHECK refuses one display name
     * (Postgres would quote the whole failing row in the error's DETAIL); ROOT is captured at DEBUG. Hibernate's own
     * DEBUG entity listing stays at INFO, as in every deployed profile: the path under test is the driver's message,
     * which Hibernate logs at ERROR before Spring translates it.
     */
    @Test
    @DisplayName("a refused copy row leaves its values in no log line and no exception (driver detail off, ADR-0072)")
    void aRefusedCopyRowLeaksNoColumnValue() {
        String marker = "REFUSE-ME-" + UUID.randomUUID().toString().substring(0, 8);
        String constraint = "s24_it_refuse_" + marker.substring(10).replace('-', '_');
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.execute("ALTER TABLE public.ext_supplier_vendor ADD CONSTRAINT " + constraint
                + " CHECK (display_name NOT LIKE '%REFUSE-ME-%')");
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Logger hibernate =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.hibernate");
        ch.qos.logback.classic.Level rootLevel = root.getLevel();
        ch.qos.logback.classic.Level hibernateLevel = hibernate.getLevel();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        root.addAppender(logs);
        root.setLevel(ch.qos.logback.classic.Level.DEBUG);
        hibernate.setLevel(ch.qos.logback.classic.Level.INFO);
        Throwable thrown;
        try {
            UUID vendorId = UUID.randomUUID();
            String fact = vendorFact(UUID.randomUUID(), vendorId, 2, "")
                    .replace("\"displayName\":\"Copy Test\"", "\"displayName\":\"" + marker + "\"");
            thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> asTenant(TENANT_A, () -> listener.onSupplierEvent(fact)));
        } finally {
            root.detachAppender(logs);
            root.setLevel(rootLevel);
            hibernate.setLevel(hibernateLevel);
            owner.execute("ALTER TABLE public.ext_supplier_vendor DROP CONSTRAINT IF EXISTS " + constraint);
        }

        assertThat(thrown)
                .as("the refusal propagates for retry, unmarked")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            assertThat(String.valueOf(cause.getMessage()))
                    .as("exception cause chain")
                    .doesNotContain(marker)
                    .doesNotContain("Failing row");
        }
        assertThat(logs.list)
                .as("a WARN or ERROR line from the refused insert is captured (the scan is not vacuous)")
                .anyMatch(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN));
        // The driver's "Failing row contains (...)" detail is in no line; the JDBC error path (WARN / ERROR) names no
        // column value. The display name is CONFIDENTIAL for a sole proprietor (ADR-0072 D1): Spring Data's DEBUG
        // "Touched <entity>" may name it, INFO and above may not.
        assertThat(logs.list.stream()
                        .filter(event -> (event.getFormattedMessage()
                                        + (event.getThrowableProxy() == null
                                                ? ""
                                                : event.getThrowableProxy().getMessage()))
                                .contains("Failing row")))
                .as("log lines carrying a failing row")
                .isEmpty();
        assertThat(logs.list.stream()
                        .filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
                        .filter(event -> event.getFormattedMessage().contains(marker)))
                .as("WARN/ERROR lines carrying the refused row's display name")
                .isEmpty();
    }

    private static int count(JdbcTemplate jdbc, UUID vendorId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ext_supplier_vendor WHERE vendor_id = ?", Integer.class, vendorId);
        return count == null ? 0 : count;
    }
}
