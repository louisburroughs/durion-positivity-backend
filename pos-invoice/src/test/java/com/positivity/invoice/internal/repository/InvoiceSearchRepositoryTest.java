package com.positivity.invoice.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Database-level contract of {@link InvoiceRepository#searchByQuery}'s structured filters
 * (#1599, E11): exact status, the {@code finalizedAt}-anchored issued-date window, exact
 * customer id, combined with each other and with the free-text leg — including the
 * filters-only path (empty {@code q}) that a caller uses to list without a search term.
 *
 * <p>Flyway is disabled (migrations are Postgres-oriented); schema comes from the JPA mappings.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_invoice_search;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InvoiceSearchRepositoryTest {

    private static final UUID PARTY_A = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");
    private static final UUID PARTY_B = UUID.fromString("018f0000-0000-7000-8000-0000000000bb");
    private static final Pageable PAGE = PageRequest.of(0, 25);
    private static final List<String> NO_PARTY = List.of("__none__");
    private static final List<UUID> NO_WORKORDER = List.of(new UUID(0L, 0L));

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private EntityManager entityManager;

    private Invoice invoice(
            String number,
            UUID partyId,
            InvoiceStatus status,
            BigDecimal total,
            Instant createdAt,
            Instant finalizedAt) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(number);
        invoice.setPartyId(partyId == null ? null : partyId.toString());
        invoice.setStatus(status);
        invoice.setTotal(total);
        invoice.setCreatedAt(createdAt);
        invoice.setUpdatedAt(createdAt);
        invoice.setFinalizedAt(finalizedAt);
        return invoice;
    }

    @Test
    void statusFilter_narrowsToExactStatus() {
        entityManager.persist(invoice(
                "INV-1",
                PARTY_A,
                InvoiceStatus.DRAFT,
                new BigDecimal("100.0000"),
                Instant.parse("2026-06-01T00:00:00Z"),
                null));
        entityManager.persist(invoice(
                "INV-2",
                PARTY_A,
                InvoiceStatus.POSTED,
                new BigDecimal("100.0000"),
                Instant.parse("2026-06-02T00:00:00Z"),
                Instant.parse("2026-06-02T00:00:00Z")));
        entityManager.flush();
        entityManager.clear();

        Page<Invoice> result = invoiceRepository.searchByQuery(
                "", NO_PARTY, NO_WORKORDER, InvoiceStatus.POSTED, null, null, null, PAGE);

        assertThat(result.getContent()).extracting(Invoice::getInvoiceNumber).containsExactly("INV-2");
    }

    @Test
    void issuedWindowFilter_matchesFinalizedAtInclusiveBounds_excludingDraftsWithNoFinalizedAt() {
        entityManager.persist(invoice(
                "INV-DRAFT",
                PARTY_A,
                InvoiceStatus.DRAFT,
                new BigDecimal("50.0000"),
                Instant.parse("2026-06-15T00:00:00Z"),
                null));
        entityManager.persist(invoice(
                "INV-IN-WINDOW",
                PARTY_A,
                InvoiceStatus.FINALIZED,
                new BigDecimal("50.0000"),
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-15T12:00:00Z")));
        entityManager.persist(invoice(
                "INV-OUT-OF-WINDOW",
                PARTY_A,
                InvoiceStatus.FINALIZED,
                new BigDecimal("50.0000"),
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-05T12:00:00Z")));
        entityManager.flush();
        entityManager.clear();

        Page<Invoice> result = invoiceRepository.searchByQuery(
                "",
                NO_PARTY,
                NO_WORKORDER,
                null,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-30T23:59:59Z"),
                null,
                PAGE);

        assertThat(result.getContent()).extracting(Invoice::getInvoiceNumber).containsExactly("INV-IN-WINDOW");
    }

    @Test
    void customerIdFilter_matchesExactPartyId() {
        entityManager.persist(invoice(
                "INV-A",
                PARTY_A,
                InvoiceStatus.POSTED,
                new BigDecimal("10.0000"),
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z")));
        entityManager.persist(invoice(
                "INV-B",
                PARTY_B,
                InvoiceStatus.POSTED,
                new BigDecimal("10.0000"),
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z")));
        entityManager.flush();
        entityManager.clear();

        Page<Invoice> result =
                invoiceRepository.searchByQuery("", NO_PARTY, NO_WORKORDER, null, null, null, PARTY_A.toString(), PAGE);

        assertThat(result.getContent()).extracting(Invoice::getInvoiceNumber).containsExactly("INV-A");
    }

    @Test
    void combinedFilters_andedTogetherAndWithFreeTextLeg() {
        entityManager.persist(invoice(
                "INV-MATCH",
                PARTY_A,
                InvoiceStatus.POSTED,
                new BigDecimal("10.0000"),
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-15T00:00:00Z")));
        // Same status/window/customer, but the free-text leg must still exclude it.
        entityManager.persist(invoice(
                "INV-OTHER-NUMBER",
                PARTY_A,
                InvoiceStatus.POSTED,
                new BigDecimal("10.0000"),
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-15T00:00:00Z")));
        entityManager.flush();
        entityManager.clear();

        Page<Invoice> result = invoiceRepository.searchByQuery(
                "MATCH",
                NO_PARTY,
                NO_WORKORDER,
                InvoiceStatus.POSTED,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-30T23:59:59Z"),
                PARTY_A.toString(),
                PAGE);

        assertThat(result.getContent()).extracting(Invoice::getInvoiceNumber).containsExactly("INV-MATCH");
    }

    @Test
    void emptyQueryWithNoFilters_matchesEverything() {
        entityManager.persist(invoice(
                "INV-1",
                PARTY_A,
                InvoiceStatus.DRAFT,
                new BigDecimal("10.0000"),
                Instant.parse("2026-06-01T00:00:00Z"),
                null));
        entityManager.persist(invoice(
                "INV-2",
                PARTY_B,
                InvoiceStatus.POSTED,
                new BigDecimal("10.0000"),
                Instant.parse("2026-06-02T00:00:00Z"),
                Instant.parse("2026-06-02T00:00:00Z")));
        entityManager.flush();
        entityManager.clear();

        // Mirrors the service-layer contract: an empty q is only ever sent to the repository
        // when at least one structured filter is set (InvoiceSearchServiceImpl short-circuits
        // the true "no q, no filters" case before calling this method) — verified here as the
        // repository's own behavior in isolation, since the repository itself has no opinion on
        // that short-circuit.
        Page<Invoice> result =
                invoiceRepository.searchByQuery("", NO_PARTY, NO_WORKORDER, null, null, null, null, PAGE);

        assertThat(result.getContent()).hasSize(2);
    }
}
