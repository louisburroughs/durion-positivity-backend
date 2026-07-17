package com.positivity.invoice.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.invoice.internal.entity.ExtWorkorderReplica;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

/**
 * Database-level contract of {@link InvoiceRepository#backfillPartyIdFromWorkorderReplica},
 * the bulk UPDATE behind the #921 party-id backfill, exercised on H2 (the statement is written
 * to run on both Postgres and H2): only NULL-party invoices are patched, the stored value is
 * the canonical lowercase UUID string, invoices whose replica row carries no customer (or has
 * no replica row at all) are untouched, and a second run is a zero-row no-op.
 *
 * <p>Flyway is disabled (migrations are Postgres-oriented); schema comes from the JPA
 * mappings, and auditing is not enabled so timestamps are pinned per row.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_invoice_party_backfill;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InvoicePartyIdBackfillRepositoryTest {

    private static final Instant CREATED = Instant.parse("2025-06-01T10:00:00Z");
    private static final UUID CUSTOMER_ID = UUID.fromString("018F0000-0000-7000-8000-0000000000AA");

    private static final UUID WO_WITH_CUSTOMER = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID WO_WITHOUT_CUSTOMER = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    private static final UUID WO_NO_REPLICA = UUID.fromString("018f0000-0000-7000-8000-000000000003");
    private static final UUID WO_ALREADY_SET = UUID.fromString("018f0000-0000-7000-8000-000000000004");

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private EntityManager entityManager;

    private UUID patchableInvoiceId;
    private UUID replicaWithoutCustomerInvoiceId;
    private UUID noReplicaInvoiceId;
    private UUID alreadySetInvoiceId;

    private Invoice invoice(String number, UUID workorderId, String partyId) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(number);
        invoice.setWorkorderId(workorderId);
        invoice.setPartyId(partyId);
        invoice.setStatus(InvoiceStatus.POSTED);
        invoice.setCreatedAt(CREATED);
        invoice.setUpdatedAt(CREATED);
        return invoice;
    }

    private ExtWorkorderReplica replica(UUID workorderId, UUID customerId) {
        return ExtWorkorderReplica.builder()
                .workorderId(workorderId)
                .workorderNumber("WO-" + workorderId.toString().substring(0, 8))
                .status("COMPLETED")
                .customerId(customerId)
                .aggregateVersion(1L)
                .updatedAt(CREATED)
                .build();
    }

    @BeforeEach
    void seed() {
        // Pre-#920 invoice (NULL party) whose replica row carries the customer: must be patched.
        Invoice patchable = invoice("INV-2025-0001", WO_WITH_CUSTOMER, null);
        entityManager.persist(patchable);
        entityManager.persist(replica(WO_WITH_CUSTOMER, CUSTOMER_ID));

        // NULL-party invoice whose replica row has no customer yet: must stay NULL.
        Invoice replicaWithoutCustomer = invoice("INV-2025-0002", WO_WITHOUT_CUSTOMER, null);
        entityManager.persist(replicaWithoutCustomer);
        entityManager.persist(replica(WO_WITHOUT_CUSTOMER, null));

        // NULL-party invoice with no replica row at all: must stay NULL.
        Invoice noReplica = invoice("INV-2025-0003", WO_NO_REPLICA, null);
        entityManager.persist(noReplica);

        // Invoice that already has a party: must never be overwritten, even though its
        // replica row carries a different customer.
        Invoice alreadySet = invoice("INV-2026-0004", WO_ALREADY_SET, "party-preexisting");
        entityManager.persist(alreadySet);
        entityManager.persist(replica(WO_ALREADY_SET, UUID.fromString("018f0000-0000-7000-8000-0000000000ff")));

        entityManager.flush();
        patchableInvoiceId = patchable.getId();
        replicaWithoutCustomerInvoiceId = replicaWithoutCustomer.getId();
        noReplicaInvoiceId = noReplica.getId();
        alreadySetInvoiceId = alreadySet.getId();
        entityManager.clear();
    }

    @Test
    void patchesOnlyNullPartyInvoicesWithACustomerBearingReplicaRow() {
        int patched = invoiceRepository.backfillPartyIdFromWorkorderReplica();
        entityManager.clear();

        assertThat(patched).isEqualTo(1);
        // Canonical lowercase UUID string — the form party-scoped lookups match on.
        assertThat(entityManager.find(Invoice.class, patchableInvoiceId).getPartyId())
                .isEqualTo(CUSTOMER_ID.toString().toLowerCase(java.util.Locale.ROOT));
        assertThat(entityManager
                        .find(Invoice.class, replicaWithoutCustomerInvoiceId)
                        .getPartyId())
                .isNull();
        assertThat(entityManager.find(Invoice.class, noReplicaInvoiceId).getPartyId())
                .isNull();
        assertThat(entityManager.find(Invoice.class, alreadySetInvoiceId).getPartyId())
                .isEqualTo("party-preexisting");
    }

    @Test
    void secondRunIsAZeroRowNoOp() {
        assertThat(invoiceRepository.backfillPartyIdFromWorkorderReplica()).isEqualTo(1);
        assertThat(invoiceRepository.backfillPartyIdFromWorkorderReplica()).isZero();
    }
}
