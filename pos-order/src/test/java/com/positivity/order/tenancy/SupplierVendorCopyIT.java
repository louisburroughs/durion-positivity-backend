package com.positivity.order.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.order.internal.entity.ExtSupplierVendor;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.exception.PurchaseOrderVendorException;
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
                            PurchaseOrderVendorException.class,
                            e -> assertThat(e.getCode()).isEqualTo(PurchaseOrderVendorException.Code.VENDOR_NOT_FOUND));
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

    private static int count(JdbcTemplate jdbc, UUID vendorId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ext_supplier_vendor WHERE vendor_id = ?", Integer.class, vendorId);
        return count == null ? 0 : count;
    }
}
