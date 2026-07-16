package com.positivity.invoice.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.InvoiceItem;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

/**
 * Database-level contract of {@link InvoiceItemRepository#findByInvoicePartyId}, the query
 * powering the pos-warranty candidate-line search (PRD-warranty-claims-module §7 step 2):
 * party filtering by exact canonical-lowercase string equality, owning-invoice
 * {@code createdAt DESC} ordering (newest sale first), initialized JOIN FETCH of the owning
 * invoice, the LIKE-escaped description filter, and the SQL-level result bound.
 *
 * <p>Flyway is disabled (migrations are Postgres-oriented); schema comes from the JPA
 * mappings, and auditing is not enabled so {@code createdAt} can be pinned per row.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_invoice_items;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InvoiceItemRepositoryTest {

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

    private Invoice invoice(String number, String partyId, Instant createdAt) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(number);
        invoice.setWorkorderId(UUID.randomUUID());
        invoice.setPartyId(partyId);
        invoice.setStatus(InvoiceStatus.POSTED);
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
        Invoice uppercaseParty =
                invoice("INV-2026-0777", PARTY_ID.toString().toUpperCase(java.util.Locale.ROOT), NEWER);
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
    void returnsOnlyMatchingPartyItemsNewestInvoiceFirstThenItemIdAscending() {
        List<InvoiceItem> items =
                invoiceItemRepository.findByInvoicePartyId(PARTY_ID.toString(), null, PageRequest.of(0, 200));

        assertThat(items)
                .extracting(InvoiceItem::getId)
                .containsExactly(newerItemId, olderFirstItemId, olderSecondItemId);
    }

    @Test
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
    void lookupIsExactEqualityOnTheCanonicalLowercaseUuidString() {
        // The uppercase-stored row shares the same UUID but is not matched: producers must
        // persist UUID.toString() (canonical lowercase), which InvoiceServiceImpl does.
        List<InvoiceItem> items =
                invoiceItemRepository.findByInvoicePartyId(PARTY_ID.toString(), null, PageRequest.of(0, 200));
        assertThat(items).extracting(InvoiceItem::getDescription).doesNotContain("Wiper blades");

        List<InvoiceItem> uppercaseLookup = invoiceItemRepository.findByInvoicePartyId(
                PARTY_ID.toString().toUpperCase(java.util.Locale.ROOT), null, PageRequest.of(0, 200));
        assertThat(uppercaseLookup).extracting(InvoiceItem::getDescription).containsExactly("Wiper blades");
    }

    @Test
    void descriptionFilterIsCaseInsensitiveAndAppliedInSql() {
        List<InvoiceItem> items =
                invoiceItemRepository.findByInvoicePartyId(PARTY_ID.toString(), "brake", PageRequest.of(0, 200));

        assertThat(items)
                .extracting(InvoiceItem::getDescription)
                .containsExactly("Front brake pads", "Brake fluid flush");
    }

    @Test
    void resultSetIsBoundedByThePageRequestInSql() {
        List<InvoiceItem> items =
                invoiceItemRepository.findByInvoicePartyId(PARTY_ID.toString(), null, PageRequest.of(0, 2));

        // Newest two only — the bound applies before materialization, keeping years of
        // history out of memory.
        assertThat(items).extracting(InvoiceItem::getId).containsExactly(newerItemId, olderFirstItemId);
    }
}
