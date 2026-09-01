package com.positivity.invoice.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.invoice.internal.entity.ExtWorkorderReplica;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.repository.InvoiceRepository.InvoicingLagPairProjection;
import com.positivity.invoice.internal.repository.InvoiceRepository.RevenueByCustomerProjection;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

/**
 * Database-level contract of {@link InvoiceRepository#revenueByCustomer} and {@link
 * InvoiceRepository#invoicingLagPairs} (issues #1589, #1592): revenue-status filtering, window
 * bounds, revenue-descending ordering, and — the correctness rule #1592 calls out explicitly —
 * that a missing {@code workorderCreatedAt} comes back as a null field on its row rather than
 * the row (or the whole invoice) disappearing or defaulting to a zero-lag value.
 *
 * <p>Flyway is disabled (migrations are Postgres-oriented); schema comes from the JPA mappings,
 * and auditing is not enabled so {@code createdAt} can be pinned per row.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_invoice_analytics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InvoiceAnalyticsRepositoryTest {

    private static final UUID PARTY_A = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");
    private static final UUID PARTY_B = UUID.fromString("018f0000-0000-7000-8000-0000000000bb");
    private static final Instant WINDOW_START = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-06-30T23:59:59Z");
    private static final Instant IN_WINDOW = Instant.parse("2026-06-15T10:00:00Z");
    private static final Instant LATER_IN_WINDOW = Instant.parse("2026-06-20T10:00:00Z");
    private static final Instant OUT_OF_WINDOW = Instant.parse("2026-07-05T10:00:00Z");

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private EntityManager entityManager;

    private Invoice invoice(String number, UUID partyId, InvoiceStatus status, BigDecimal total, Instant createdAt) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(number);
        invoice.setPartyId(partyId == null ? null : partyId.toString());
        invoice.setStatus(status);
        invoice.setTotal(total);
        invoice.setCreatedAt(createdAt);
        invoice.setUpdatedAt(createdAt);
        return invoice;
    }

    // ==================== revenueByCustomer ====================

    @Test
    void groupsByPartyId_sumsRevenue_countsInvoices_andTracksLastInvoiceDate() {
        entityManager.persist(
                invoice("INV-1", PARTY_A, InvoiceStatus.FINALIZED, new BigDecimal("100.0000"), IN_WINDOW));
        entityManager.persist(
                invoice("INV-2", PARTY_A, InvoiceStatus.POSTED, new BigDecimal("50.0000"), LATER_IN_WINDOW));
        entityManager.flush();
        entityManager.clear();

        List<RevenueByCustomerProjection> rows = invoiceRepository.revenueByCustomer(
                WINDOW_START,
                WINDOW_END,
                EnumSet.of(InvoiceStatus.FINALIZED, InvoiceStatus.POSTED),
                PageRequest.of(0, 10));

        assertThat(rows).hasSize(1);
        RevenueByCustomerProjection row = rows.getFirst();
        assertThat(row.getCustomerId()).isEqualTo(PARTY_A.toString());
        assertThat(row.getRevenue()).isEqualByComparingTo("150.0000");
        assertThat(row.getInvoiceCount()).isEqualTo(2);
        assertThat(row.getLastInvoiceDate()).isEqualTo(LATER_IN_WINDOW);
    }

    @Test
    void ordersByRevenueDescending() {
        entityManager.persist(invoice("INV-1", PARTY_A, InvoiceStatus.FINALIZED, new BigDecimal("10.0000"), IN_WINDOW));
        entityManager.persist(
                invoice("INV-2", PARTY_B, InvoiceStatus.FINALIZED, new BigDecimal("999.0000"), IN_WINDOW));
        entityManager.flush();
        entityManager.clear();

        List<RevenueByCustomerProjection> rows = invoiceRepository.revenueByCustomer(
                WINDOW_START,
                WINDOW_END,
                EnumSet.of(InvoiceStatus.FINALIZED, InvoiceStatus.POSTED),
                PageRequest.of(0, 10));

        assertThat(rows)
                .extracting(RevenueByCustomerProjection::getCustomerId)
                .containsExactly(PARTY_B.toString(), PARTY_A.toString());
    }

    @Test
    void excludesDraftCancelledAndErrorInvoices() {
        entityManager.persist(invoice("INV-1", PARTY_A, InvoiceStatus.DRAFT, new BigDecimal("500.0000"), IN_WINDOW));
        entityManager.persist(
                invoice("INV-2", PARTY_A, InvoiceStatus.CANCELLED, new BigDecimal("500.0000"), IN_WINDOW));
        entityManager.persist(invoice("INV-3", PARTY_A, InvoiceStatus.ERROR, new BigDecimal("500.0000"), IN_WINDOW));
        entityManager.flush();
        entityManager.clear();

        List<RevenueByCustomerProjection> rows = invoiceRepository.revenueByCustomer(
                WINDOW_START,
                WINDOW_END,
                EnumSet.of(InvoiceStatus.FINALIZED, InvoiceStatus.POSTED),
                PageRequest.of(0, 10));

        assertThat(rows).isEmpty();
    }

    @Test
    void excludesInvoicesOutsideTheWindowAndWithNoPartyId() {
        entityManager.persist(
                invoice("INV-1", PARTY_A, InvoiceStatus.FINALIZED, new BigDecimal("100.0000"), OUT_OF_WINDOW));
        entityManager.persist(invoice("INV-2", null, InvoiceStatus.FINALIZED, new BigDecimal("100.0000"), IN_WINDOW));
        entityManager.flush();
        entityManager.clear();

        List<RevenueByCustomerProjection> rows = invoiceRepository.revenueByCustomer(
                WINDOW_START,
                WINDOW_END,
                EnumSet.of(InvoiceStatus.FINALIZED, InvoiceStatus.POSTED),
                PageRequest.of(0, 10));

        assertThat(rows).isEmpty();
    }

    @Test
    void pageableBoundsTheRowCount() {
        entityManager.persist(
                invoice("INV-1", PARTY_A, InvoiceStatus.FINALIZED, new BigDecimal("300.0000"), IN_WINDOW));
        entityManager.persist(
                invoice("INV-2", PARTY_B, InvoiceStatus.FINALIZED, new BigDecimal("200.0000"), IN_WINDOW));
        entityManager.flush();
        entityManager.clear();

        List<RevenueByCustomerProjection> rows = invoiceRepository.revenueByCustomer(
                WINDOW_START,
                WINDOW_END,
                EnumSet.of(InvoiceStatus.FINALIZED, InvoiceStatus.POSTED),
                PageRequest.of(0, 1));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getCustomerId()).isEqualTo(PARTY_A.toString());
    }

    // ==================== invoicingLagPairs ====================

    @Test
    void joinsWorkorderCreatedAt_whenReplicaHasIt() {
        UUID workorderId = UUID.randomUUID();
        entityManager.persist(ExtWorkorderReplica.builder()
                .workorderId(workorderId)
                .aggregateVersion(1)
                .workorderCreatedAt(Instant.parse("2026-06-10T08:00:00Z"))
                .updatedAt(IN_WINDOW)
                .build());
        Invoice invoice = invoice("INV-1", PARTY_A, InvoiceStatus.FINALIZED, new BigDecimal("100.0000"), IN_WINDOW);
        invoice.setWorkorderId(workorderId);
        entityManager.persist(invoice);
        entityManager.flush();
        entityManager.clear();

        List<InvoicingLagPairProjection> pairs = invoiceRepository.invoicingLagPairs(WINDOW_START, WINDOW_END);

        assertThat(pairs).hasSize(1);
        assertThat(pairs.getFirst().getInvoiceCreatedAt()).isEqualTo(IN_WINDOW);
        assertThat(pairs.getFirst().getWorkorderCreatedAt()).isEqualTo(Instant.parse("2026-06-10T08:00:00Z"));
    }

    @Test
    void returnsNullWorkorderCreatedAt_whenReplicaRowExistsButFieldIsNull() {
        UUID workorderId = UUID.randomUUID();
        entityManager.persist(ExtWorkorderReplica.builder()
                .workorderId(workorderId)
                .aggregateVersion(1)
                .workorderCreatedAt(null)
                .updatedAt(IN_WINDOW)
                .build());
        Invoice invoice = invoice("INV-1", PARTY_A, InvoiceStatus.FINALIZED, new BigDecimal("100.0000"), IN_WINDOW);
        invoice.setWorkorderId(workorderId);
        entityManager.persist(invoice);
        entityManager.flush();
        entityManager.clear();

        List<InvoicingLagPairProjection> pairs = invoiceRepository.invoicingLagPairs(WINDOW_START, WINDOW_END);

        // The row must still come back — a null lag anchor is the caller's problem to exclude,
        // not this query's problem to hide.
        assertThat(pairs).hasSize(1);
        assertThat(pairs.getFirst().getWorkorderCreatedAt()).isNull();
    }

    @Test
    void returnsNullWorkorderCreatedAt_whenNoReplicaRowExistsAtAll() {
        Invoice invoice = invoice("INV-1", PARTY_A, InvoiceStatus.FINALIZED, new BigDecimal("100.0000"), IN_WINDOW);
        invoice.setWorkorderId(UUID.randomUUID());
        entityManager.persist(invoice);
        entityManager.flush();
        entityManager.clear();

        List<InvoicingLagPairProjection> pairs = invoiceRepository.invoicingLagPairs(WINDOW_START, WINDOW_END);

        assertThat(pairs).hasSize(1);
        assertThat(pairs.getFirst().getWorkorderCreatedAt()).isNull();
    }

    @Test
    void excludesInvoicesWithNoLinkedWorkorder() {
        Invoice invoice = invoice("INV-1", PARTY_A, InvoiceStatus.FINALIZED, new BigDecimal("100.0000"), IN_WINDOW);
        // workorderId left null: an order-only invoice (parity story C2).
        entityManager.persist(invoice);
        entityManager.flush();
        entityManager.clear();

        List<InvoicingLagPairProjection> pairs = invoiceRepository.invoicingLagPairs(WINDOW_START, WINDOW_END);

        assertThat(pairs).isEmpty();
    }

    @Test
    void excludesInvoicesOutsideTheWindow() {
        UUID workorderId = UUID.randomUUID();
        entityManager.persist(ExtWorkorderReplica.builder()
                .workorderId(workorderId)
                .aggregateVersion(1)
                .workorderCreatedAt(Instant.parse("2026-06-10T08:00:00Z"))
                .updatedAt(OUT_OF_WINDOW)
                .build());
        Invoice invoice = invoice("INV-1", PARTY_A, InvoiceStatus.FINALIZED, new BigDecimal("100.0000"), OUT_OF_WINDOW);
        invoice.setWorkorderId(workorderId);
        entityManager.persist(invoice);
        entityManager.flush();
        entityManager.clear();

        List<InvoicingLagPairProjection> pairs = invoiceRepository.invoicingLagPairs(WINDOW_START, WINDOW_END);

        assertThat(pairs).isEmpty();
    }
}
