package com.positivity.invoice.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.invoice.PostgresSliceTestBase;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.InvoiceItem;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.enums.PaymentTerms;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * Database-level contract of {@link InvoiceItemRepository#findByInvoicePartyId}, the query powering
 * the pos-warranty candidate-line search (PRD-warranty-claims-module §7 step 2): party filtering by
 * exact canonical-lowercase string equality, owning-invoice {@code createdAt DESC} ordering (newest
 * sale first), initialized JOIN FETCH of the owning invoice, the LIKE-escaped description filter,
 * and the SQL-level result bound.
 *
 * <p>It runs against the real PostgreSQL baseline rather than the H2 schema it used to boot, and
 * that move is what exposed issue #1891 here. Every unfiltered case below — the majority, since the
 * description filter is optional and the endpoint's main job is "show me this party's lines" — used
 * to throw on PostgreSQL and pass on H2. The search was one JPQL string containing
 * {@code (:q IS NULL OR LOWER(ii.description) LIKE LOWER(CONCAT('%', :q, '%')))}, and Hibernate binds
 * the null of that parameter untyped: PostgreSQL then has to resolve {@code unknown || unknown} on
 * its own, settles on {@code bytea}, and rejects the statement at parse time with {@code function
 * lower(bytea) does not exist}. A supplied term hid the defect completely, because a bound varchar
 * types the operator and the same statement parses. {@code GET /v1/invoices/items/search} answered
 * 500 to every call that omitted {@code q}.
 */
@DisplayName("Invoice-line search on PostgreSQL")
class InvoiceItemRepositoryTest extends PostgresSliceTestBase {

    private static final UUID PARTY_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");
    private static final UUID OTHER_PARTY_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000bb");
    private static final Instant OLDER = Instant.parse("2025-06-01T10:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-07-01T10:00:00Z");

    @Autowired
    private InvoiceItemRepository invoiceItemRepository;

    @Autowired
    private EntityManager entityManager;

    private UUID olderFirstItemId;
    private UUID olderSecondItemId;
    private UUID newerItemId;

    /**
     * The slice does not enable JPA auditing, so the fixture pins {@code createdAt}/{@code updatedAt}
     * itself — which is also what lets the ordering assertions below control the sale order exactly.
     * {@code invoices_finalized_due_date_check} in the baseline additionally requires a POSTED
     * invoice to carry the due date and payment terms frozen at finalization (#993); the H2 schema
     * had no such constraint and let one exist without them, which the application never can.
     */
    private Invoice invoice(String number, String partyId, Instant createdAt) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(number);
        invoice.setWorkorderId(UUID.randomUUID());
        invoice.setPartyId(partyId);
        invoice.setStatus(InvoiceStatus.POSTED);
        invoice.setDueDate(LocalDate.of(2026, 12, 31));
        invoice.setPaymentTermsCode(PaymentTerms.NET_30.name());
        invoice.setCreatedAt(createdAt);
        invoice.setUpdatedAt(createdAt);
        return invoice;
    }

    private InvoiceItem item(Invoice invoice, String description) {
        InvoiceItem item = new InvoiceItem();
        item.setDescription(description);
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(new BigDecimal("100.0000"));
        item.setLineTotal(new BigDecimal("100.0000"));
        invoice.addItem(item);
        return item;
    }

    @BeforeEach
    void seed() {
        // Older invoice for the party, two items (id order inside one invoice must be stable).
        Invoice older = invoice("INV-2025-0001", PARTY_ID.toString(), OLDER);
        InvoiceItem olderFirst = item(older, "Front brake pads");
        InvoiceItem olderSecond = item(older, "Brake fluid flush");
        entityManager.persist(older);

        // Newer invoice for the same party.
        Invoice newer = invoice("INV-2026-0042", PARTY_ID.toString(), NEWER);
        InvoiceItem newerItem = item(newer, "All-season tire 205/55R16");
        entityManager.persist(newer);

        // Another party entirely: must never appear.
        Invoice otherParty = invoice("INV-2026-0099", OTHER_PARTY_ID.toString(), NEWER);
        item(otherParty, "Front brake pads");
        entityManager.persist(otherParty);

        // Same UUID but non-canonical (uppercase) stored form: documents that the lookup is
        // exact string equality on the canonical lowercase UUID.toString() form.
        Invoice uppercaseParty = invoice("INV-2026-0777", PARTY_ID.toString().toUpperCase(Locale.ROOT), NEWER);
        item(uppercaseParty, "Wiper blades");
        entityManager.persist(uppercaseParty);

        entityManager.flush();
        olderFirstItemId = olderFirst.getId();
        olderSecondItemId = olderSecond.getId();
        newerItemId = newerItem.getId();
        // Clear the persistence context so the JOIN FETCH is exercised against the database,
        // not satisfied from already-managed instances.
        entityManager.clear();
    }

    @Test
    @DisplayName("only the party's lines come back, newest invoice first then item id ascending")
    void returnsOnlyMatchingPartyItemsNewestInvoiceFirstThenItemIdAscending() {
        List<InvoiceItem> items =
                invoiceItemRepository.findByInvoicePartyId(PARTY_ID.toString(), null, PageRequest.of(0, 200));

        assertThat(items)
                .extracting(InvoiceItem::getId)
                .containsExactly(newerItemId, olderFirstItemId, olderSecondItemId);
    }

    @Test
    @DisplayName("the owning invoice is join-fetched, so the mapping can read it outside a loading session")
    void owningInvoiceIsJoinFetchedSoMappingCanReadItOutsideALoadingSession() {
        List<InvoiceItem> items =
                invoiceItemRepository.findByInvoicePartyId(PARTY_ID.toString(), null, PageRequest.of(0, 200));

        assertThat(items).isNotEmpty().allSatisfy(item -> {
            assertThat(Hibernate.isInitialized(item.getInvoice())).isTrue();
            assertThat(item.getInvoice().getCreatedAt()).isNotNull();
        });
        assertThat(items.getFirst().getInvoice().getInvoiceNumber()).isEqualTo("INV-2026-0042");
    }

    @Test
    @DisplayName("the party lookup is exact equality on the canonical lowercase UUID string")
    void lookupIsExactEqualityOnTheCanonicalLowercaseUuidString() {
        // The uppercase-stored row shares the same UUID but is not matched: producers must
        // persist UUID.toString() (canonical lowercase), which InvoiceServiceImpl does.
        List<InvoiceItem> items =
                invoiceItemRepository.findByInvoicePartyId(PARTY_ID.toString(), null, PageRequest.of(0, 200));
        assertThat(items).extracting(InvoiceItem::getDescription).doesNotContain("Wiper blades");

        List<InvoiceItem> uppercaseLookup = invoiceItemRepository.findByInvoicePartyId(
                PARTY_ID.toString().toUpperCase(Locale.ROOT), null, PageRequest.of(0, 200));
        assertThat(uppercaseLookup).extracting(InvoiceItem::getDescription).containsExactly("Wiper blades");
    }

    @Test
    @DisplayName("the description filter is case-insensitive and applied in SQL")
    void descriptionFilterIsCaseInsensitiveAndAppliedInSql() {
        List<InvoiceItem> items =
                invoiceItemRepository.findByInvoicePartyId(PARTY_ID.toString(), "brake", PageRequest.of(0, 200));

        assertThat(items)
                .extracting(InvoiceItem::getDescription)
                .containsExactly("Front brake pads", "Brake fluid flush");
    }

    @Test
    @DisplayName("a LIKE metacharacter in the filter is matched literally")
    void likeMetacharacterInTheFilterIsMatchedLiterally() {
        Invoice discounted = invoice("INV-2026-0500", PARTY_ID.toString(), NEWER);
        item(discounted, "50% off labor");
        item(discounted, "5099 off labor");
        entityManager.persist(discounted);
        entityManager.flush();
        entityManager.clear();

        // The caller escapes the term; the search's ESCAPE clause has to honour it, or '%' would
        // widen the match to the sibling line.
        assertThat(invoiceItemRepository.findByInvoicePartyId(PARTY_ID.toString(), "50\\% off", PageRequest.of(0, 200)))
                .extracting(InvoiceItem::getDescription)
                .containsExactly("50% off labor");
    }

    @Test
    @DisplayName("the result set is bounded by the page request in SQL")
    void resultSetIsBoundedByThePageRequestInSql() {
        List<InvoiceItem> items =
                invoiceItemRepository.findByInvoicePartyId(PARTY_ID.toString(), null, PageRequest.of(0, 2));

        // Newest two only — the bound applies before materialization, keeping years of
        // history out of memory.
        assertThat(items).extracting(InvoiceItem::getId).containsExactly(newerItemId, olderFirstItemId);
    }

    @Test
    @DisplayName("an unpaged request returns every line rather than failing")
    void unpagedRequestReturnsEveryLine() {
        // Pageable.unpaged() reports a page size of zero, which a limit derived from it would
        // reject; the search has to carry the unpaged case through rather than throw.
        assertThat(invoiceItemRepository.findByInvoicePartyId(
                        PARTY_ID.toString(), null, org.springframework.data.domain.Pageable.unpaged()))
                .extracting(InvoiceItem::getId)
                .containsExactly(newerItemId, olderFirstItemId, olderSecondItemId);
    }
}
