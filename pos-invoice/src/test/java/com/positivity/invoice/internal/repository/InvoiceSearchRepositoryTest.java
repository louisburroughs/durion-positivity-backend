package com.positivity.invoice.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.invoice.PostgresSliceTestBase;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.enums.PaymentTerms;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Database-level contract of {@link InvoiceRepository#searchByQuery}: the free-text leg and the
 * structured filters of #1599 (E11) — exact status, the {@code finalizedAt}-anchored issued-date
 * window, exact customer id — each on its own, combined, and all absent.
 *
 * <p>It runs against the real PostgreSQL baseline rather than the H2 schema it used to boot, because
 * what several of these cases guard is a property of PostgreSQL alone. The finder was one JPQL
 * string of {@code (:param IS NULL OR column = :param)} clauses, two of whose placeholders are
 * {@link Instant} bounds on {@code finalizedAt}. pgjdbc sends a temporal value — and a temporal
 * {@code setNull} — with the type OID left unspecified so the server may coerce between
 * {@code timestamp} and {@code timestamptz}, which leaves {@code ? IS NULL} over one with nothing to
 * infer a type from; PostgreSQL rejects the whole statement at parse time with {@code could not
 * determine data type of parameter $9}, before any value is bound. Every call to
 * {@code GET /v1/invoices/search} was a 500 — filters supplied or not — while this very test passed
 * on H2 (issue #1891).
 *
 * <p>So every case below asserts a result, not an exception: the point is that the statement parses
 * and answers. The filter is now an {@code InvoiceSearch} specification, which emits no SQL at all
 * for an absent filter and so cannot reintroduce an untyped placeholder.
 */
@DisplayName("Invoice finder on PostgreSQL")
class InvoiceSearchRepositoryTest extends PostgresSliceTestBase {

    private static final UUID PARTY_A = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");
    private static final UUID PARTY_B = UUID.fromString("018f0000-0000-7000-8000-0000000000bb");
    private static final Pageable PAGE = PageRequest.of(0, 25);
    private static final Instant CREATED_AT = Instant.parse("2026-06-01T00:00:00Z");

    /** No reference ids resolved: the leg contributes nothing rather than matching nothing. */
    private static final List<String> NO_PARTY = List.of();

    private static final List<UUID> NO_WORKORDER = List.of();

    @Autowired
    private InvoiceRepository invoiceRepository;

    /**
     * {@code invoices_finalized_due_date_check} in the baseline requires every FINALIZED or POSTED
     * invoice to carry the due date and payment terms frozen at finalization (#993), so a fixture
     * that issues one has to record both — the H2 schema this test used to run against had no such
     * constraint and let an issued invoice with neither exist, which the application never can. The
     * slice does not enable JPA auditing, so the fixture also pins its own {@code createdAt} and
     * {@code updatedAt}, both of which are {@code NOT NULL}.
     */
    private Invoice invoice(String number, UUID partyId, InvoiceStatus status, Instant finalizedAt) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(number);
        invoice.setPartyId(partyId == null ? null : partyId.toString());
        invoice.setStatus(status);
        invoice.setFinalizedAt(finalizedAt);
        if (status == InvoiceStatus.FINALIZED || status == InvoiceStatus.POSTED) {
            invoice.setDueDate(LocalDate.of(2026, 12, 31));
            invoice.setPaymentTermsCode(PaymentTerms.NET_30.name());
        }
        invoice.setCreatedAt(CREATED_AT);
        invoice.setUpdatedAt(CREATED_AT);
        invoice.setSubtotal(new BigDecimal("10.0000"));
        invoice.setTax(BigDecimal.ZERO);
        invoice.setTotal(new BigDecimal("10.0000"));
        return invoiceRepository.saveAndFlush(invoice);
    }

    @Test
    @DisplayName("an empty query with no filters parses and matches every invoice")
    void emptyQueryWithNoFiltersMatchesEverything() {
        invoice("INV-1", PARTY_A, InvoiceStatus.DRAFT, null);
        invoice("INV-2", PARTY_B, InvoiceStatus.POSTED, Instant.parse("2026-06-02T00:00:00Z"));

        // Every optional filter absent: the call that used to be rejected at parse time. It mirrors
        // the service-layer contract only loosely — InvoiceSearchServiceImpl short-circuits the true
        // "no q, no filters" case before calling this method — but the repository itself has no
        // opinion on that short-circuit, and this is its behaviour in isolation.
        Page<Invoice> result =
                invoiceRepository.searchByQuery("", NO_PARTY, NO_WORKORDER, null, null, null, null, PAGE);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("the status filter narrows to the exact status")
    void statusFilterNarrowsToExactStatus() {
        invoice("INV-1", PARTY_A, InvoiceStatus.DRAFT, null);
        invoice("INV-2", PARTY_A, InvoiceStatus.POSTED, Instant.parse("2026-06-02T00:00:00Z"));

        Page<Invoice> result = invoiceRepository.searchByQuery(
                "", NO_PARTY, NO_WORKORDER, InvoiceStatus.POSTED, null, null, null, PAGE);

        assertThat(result.getContent()).extracting(Invoice::getInvoiceNumber).containsExactly("INV-2");
    }

    @Test
    @DisplayName("the issued window matches finalizedAt on both inclusive bounds and excludes drafts")
    void issuedWindowMatchesInclusiveBoundsExcludingDrafts() {
        invoice("INV-DRAFT", PARTY_A, InvoiceStatus.DRAFT, null);
        invoice("INV-ON-LOWER-BOUND", PARTY_A, InvoiceStatus.FINALIZED, Instant.parse("2026-06-01T00:00:00Z"));
        invoice("INV-ON-UPPER-BOUND", PARTY_A, InvoiceStatus.FINALIZED, Instant.parse("2026-06-30T23:59:59Z"));
        invoice("INV-OUT-OF-WINDOW", PARTY_A, InvoiceStatus.FINALIZED, Instant.parse("2026-07-05T12:00:00Z"));

        Page<Invoice> result = invoiceRepository.searchByQuery(
                "",
                NO_PARTY,
                NO_WORKORDER,
                null,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-30T23:59:59Z"),
                null,
                PAGE);

        // A DRAFT has no finalizedAt, so it is outside every window — including this one, which
        // spans its creation.
        assertThat(result.getContent())
                .extracting(Invoice::getInvoiceNumber)
                .containsExactlyInAnyOrder("INV-ON-LOWER-BOUND", "INV-ON-UPPER-BOUND");
    }

    @Test
    @DisplayName("each window bound narrows the result on its own")
    void eachWindowBoundNarrowsOnItsOwn() {
        invoice("INV-JAN", PARTY_A, InvoiceStatus.FINALIZED, Instant.parse("2026-01-15T00:00:00Z"));
        invoice("INV-JUN", PARTY_A, InvoiceStatus.FINALIZED, Instant.parse("2026-06-15T00:00:00Z"));

        assertThat(invoiceRepository
                        .searchByQuery(
                                "",
                                NO_PARTY,
                                NO_WORKORDER,
                                null,
                                Instant.parse("2026-03-01T00:00:00Z"),
                                null,
                                null,
                                PAGE)
                        .getContent())
                .extracting(Invoice::getInvoiceNumber)
                .containsExactly("INV-JUN");
        assertThat(invoiceRepository
                        .searchByQuery(
                                "",
                                NO_PARTY,
                                NO_WORKORDER,
                                null,
                                null,
                                Instant.parse("2026-03-01T00:00:00Z"),
                                null,
                                PAGE)
                        .getContent())
                .extracting(Invoice::getInvoiceNumber)
                .containsExactly("INV-JAN");
    }

    @Test
    @DisplayName("the customer filter matches the exact party id")
    void customerFilterMatchesExactPartyId() {
        invoice("INV-A", PARTY_A, InvoiceStatus.POSTED, Instant.parse("2026-06-01T00:00:00Z"));
        invoice("INV-B", PARTY_B, InvoiceStatus.POSTED, Instant.parse("2026-06-01T00:00:00Z"));

        Page<Invoice> result =
                invoiceRepository.searchByQuery("", NO_PARTY, NO_WORKORDER, null, null, null, PARTY_A.toString(), PAGE);

        assertThat(result.getContent()).extracting(Invoice::getInvoiceNumber).containsExactly("INV-A");
    }

    @Test
    @DisplayName(
            "the free-text leg matches the invoice number case-insensitively, a resolved party and a resolved workorder")
    void freeTextLegMatchesNumberPartyAndWorkorder() {
        invoice("INV-ABC-1", PARTY_A, InvoiceStatus.FINALIZED, null);
        invoice("INV-ZZZ-2", PARTY_B, InvoiceStatus.FINALIZED, null);
        Invoice byWorkorder = invoice("INV-ZZZ-3", null, InvoiceStatus.FINALIZED, null);
        UUID workorderId = UUID.randomUUID();
        byWorkorder.setWorkorderId(workorderId);
        invoiceRepository.saveAndFlush(byWorkorder);

        assertThat(invoiceRepository
                        .searchByQuery("abc-1", NO_PARTY, NO_WORKORDER, null, null, null, null, PAGE)
                        .getContent())
                .extracting(Invoice::getInvoiceNumber)
                .containsExactly("INV-ABC-1");
        assertThat(invoiceRepository
                        .searchByQuery(
                                "no-such-number",
                                List.of(PARTY_B.toString()),
                                NO_WORKORDER,
                                null,
                                null,
                                null,
                                null,
                                PAGE)
                        .getContent())
                .extracting(Invoice::getInvoiceNumber)
                .containsExactly("INV-ZZZ-2");
        assertThat(invoiceRepository
                        .searchByQuery("no-such-number", NO_PARTY, List.of(workorderId), null, null, null, null, PAGE)
                        .getContent())
                .extracting(Invoice::getInvoiceNumber)
                .containsExactly("INV-ZZZ-3");
    }

    @Test
    @DisplayName("a LIKE metacharacter in the query term is matched literally")
    void likeMetacharacterIsMatchedLiterally() {
        invoice("INV-50%-OFF", PARTY_A, InvoiceStatus.FINALIZED, null);
        invoice("INV-5099-OFF", PARTY_A, InvoiceStatus.FINALIZED, null);

        // The service escapes the term before handing it over; '%' must not widen the match to the
        // sibling invoice.
        assertThat(invoiceRepository
                        .searchByQuery("50\\%-off", NO_PARTY, NO_WORKORDER, null, null, null, null, PAGE)
                        .getContent())
                .extracting(Invoice::getInvoiceNumber)
                .containsExactly("INV-50%-OFF");
    }

    @Test
    @DisplayName("the filters are ANDed with each other and with the free-text leg")
    void combinedFiltersAndedTogetherAndWithFreeTextLeg() {
        invoice("INV-MATCH", PARTY_A, InvoiceStatus.POSTED, Instant.parse("2026-06-15T00:00:00Z"));
        // Same status, window and customer, but the free-text leg must still exclude it.
        invoice("INV-OTHER-NUMBER", PARTY_A, InvoiceStatus.POSTED, Instant.parse("2026-06-15T00:00:00Z"));

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
    @DisplayName("an unpaged search returns every match rather than failing")
    void unpagedSearchReturnsEveryMatch() {
        invoice("INV-U1", PARTY_A, InvoiceStatus.DRAFT, null);
        invoice("INV-U2", PARTY_B, InvoiceStatus.DRAFT, null);

        // Pageable.unpaged() reports a page size of zero, which PageRequest.of rejects; a search
        // that rebuilds the pageable has to carry the unpaged case through rather than throw.
        assertThat(invoiceRepository
                        .searchByQuery("", NO_PARTY, NO_WORKORDER, null, null, null, null, Pageable.unpaged())
                        .getContent())
                .hasSize(2);
    }
}
