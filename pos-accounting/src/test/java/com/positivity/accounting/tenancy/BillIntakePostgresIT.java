package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.billintake.service.BillIntakePort;
import com.positivity.accounting.internal.billintake.service.VendorlessHoldConverter;
import com.positivity.domainevents.supplier.SupplierInvoiceReceivedV1;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bill intake on the real schema (#2518, Part A): V37's tables under row-level security, a vendor-less EDI fact
 * becoming a draft with its ingestion record, and the one-shot conversion of a {@code VENDOR_ID_MISSING} hold.
 *
 * <p>Requires Docker.
 */
@DisplayName("Bill intake (#2518 Part A, real Postgres)")
class BillIntakePostgresIT extends PostgresTenancyTestBase {

    @Autowired
    private BillIntakePort port;

    @Autowired
    private VendorlessHoldConverter converter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private javax.sql.DataSource appDataSource;

    private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

    @AfterEach
    void cleanUp() {
        owner.update("DELETE FROM accounting_event WHERE source_system = 'pos-supplier' AND tenant_id IN (?, ?)", TENANT_A, TENANT_B);
        owner.update("DELETE FROM supplier_invoice_hold WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
        owner.update("DELETE FROM bill_intake_item WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
    }

    private static SupplierInvoiceReceivedV1 vendorlessFact(String number) {
        return new SupplierInvoiceReceivedV1(
                UUID.randomUUID(),
                "ACME",
                number,
                LocalDate.of(2026, 10, 1),
                SupplierInvoiceReceivedV1.Type.INVOICE,
                "USD",
                new BigDecimal("240.00"),
                new BigDecimal("48.00"),
                new BigDecimal("288.00"),
                null,
                Instant.parse("2026-10-01T00:00:00Z"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private BillIntakePort.MachineDocumentOutcome accept(UUID tenant, SupplierInvoiceReceivedV1 fact, String eventId) {
        return asTenant(
                tenant,
                () -> new TransactionTemplate(transactionManager)
                        .execute(_ -> port.acceptMachineDocument(fact, eventId)));
    }

    @Test
    @DisplayName("a vendor-less fact is a draft: one row, EDI, READY_FOR_REVIEW, one ingestion record keyed on its id")
    void vendorlessFactIsADraft() {
        provisionAccounting(TENANT_A);
        String eventId = UUID.randomUUID().toString();

        var outcome = (BillIntakePort.MachineDocumentOutcome.Draft) accept(TENANT_A, vendorlessFact("INV-1"), eventId);

        assertThat(outcome.intakeReference()).matches("BI-\\d{6}-\\d{7}");
        assertThat(owner.queryForMap(
                        "SELECT channel, status, vendor_resolution, uploaded_by, tenant_id"
                                + " FROM bill_intake_item WHERE intake_id = ?",
                        outcome.intakeId()))
                .containsEntry("channel", "EDI")
                .containsEntry("status", "READY_FOR_REVIEW")
                .containsEntry("vendor_resolution", "NOT_SET_UP")
                .containsEntry("uploaded_by", "supplier")
                .containsEntry("tenant_id", TENANT_A);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM accounting_event WHERE domain_key_id = ?",
                        Integer.class,
                        outcome.intakeId().toString()))
                .isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM vendor_bill WHERE intake_id = ?", Integer.class, outcome.intakeId()))
                .isZero();
    }

    @Test
    @DisplayName("each tenant sees only its own drafts under RLS; references are per tenant")
    void tenantsAreIsolated() {
        provisionAccounting(TENANT_A);
        provisionAccounting(TENANT_B);
        accept(TENANT_A, vendorlessFact("INV-A"), UUID.randomUUID().toString());
        accept(TENANT_B, vendorlessFact("INV-B"), UUID.randomUUID().toString());

        assertThat(asTenant(
                        TENANT_A,
                        () -> new JdbcTemplate(appDataSource)
                                .queryForList("SELECT tenant_id FROM bill_intake_item", UUID.class)))
                .containsOnly(TENANT_A);
        assertThat(asTenant(
                        TENANT_B,
                        () -> new JdbcTemplate(appDataSource)
                                .queryForList("SELECT tenant_id FROM bill_intake_item", UUID.class)))
                .containsOnly(TENANT_B);
    }

    @Test
    @DisplayName("the schema refuses a status or channel outside the lifecycle")
    void checksHold() {
        assertThatThrownBy(() -> owner.update(
                        "INSERT INTO bill_intake_item (tenant_id, intake_id, intake_reference, channel, status,"
                                + " uploaded_by, uploaded_at, created_at, updated_at)"
                                + " VALUES (?, ?, 'BI-X', 'EDI', 'BOGUS', 'x', now(), now(), now())",
                        TENANT_A,
                        UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a held VENDOR_ID_MISSING fact is converted once: draft written, hold released with no bill")
    void holdIsConvertedOnce() {
        provisionAccounting(TENANT_A);
        UUID holdId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        owner.update(
                "INSERT INTO supplier_invoice_hold (tenant_id, hold_id, event_id, supplier_invoice_ref, vendor_id,"
                        + " reason, payload, received_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'INV-9', NULL, 'VENDOR_ID_MISSING', ?::jsonb, now(), now(), now())",
                TENANT_A,
                holdId,
                eventId,
                """
                {"eventId":"%s","eventType":"supplier.invoice.received","payload":{
                 "vendorProfileId":"%s","supplierRef":"ACME","vendorInvoiceNumber":"INV-9",
                 "invoiceDate":"2026-09-30","type":"INVOICE","currency":"USD","totalGrossAmount":10.00,
                 "occurredAt":"2026-09-30T00:00:00Z","lines":[]}}
                """.formatted(eventId, UUID.randomUUID()));

        assertThat(asTenant(TENANT_A, converter::convertOpenHolds)).isEqualTo(1);
        assertThat(asTenant(TENANT_A, converter::convertOpenHolds)).isZero();

        var released = owner.queryForMap(
                "SELECT released_at, released_bill_id, released_intake_id FROM supplier_invoice_hold WHERE hold_id = ?",
                holdId);
        assertThat(released.get("released_at")).isNotNull();
        assertThat(released.get("released_bill_id")).isNull();
        assertThat(released.get("released_intake_id")).isNotNull();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM bill_intake_item WHERE intake_id = ?",
                        Integer.class,
                        released.get("released_intake_id")))
                .isEqualTo(1);
    }
}
