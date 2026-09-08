package com.positivity.supplier.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.TestClockConfig;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * The two database-level guarantees of the transmission ledger, asserted against a real schema
 * rather than a mock: the duplicate-order constraint, and the poll ordering.
 *
 * <p>Both are properties of the <em>database</em>, so a mocked repository would prove nothing
 * about either. The unique index is the last line of defence against a second physical order, and
 * the ordering is where a null sorts — which is a dialect behaviour, not application logic.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_intent;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                    + "com.positivity.supplier.internal.repository.SupplierTransmissionIntentRepositoryTest"
                    + "$CapturedSql"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestClockConfig.class})
@DisplayName("Transmission ledger persistence (ADR-0052, #1226/#1318)")
class SupplierTransmissionIntentRepositoryTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID PURCHASE_ORDER_ID = UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-000000000001");

    @Autowired
    private SupplierTransmissionIntentRepository intentRepository;

    @Autowired
    private EntityManager entityManager;

    private SupplierTransmissionIntentEntity intent(String documentId, String activeKey, Instant lastPolledAt) {
        return SupplierTransmissionIntentEntity.builder()
                .vendorProfileId(PROFILE_ID)
                .supplierRef("michelin-eu")
                .purchaseOrderId(PURCHASE_ORDER_ID)
                .intentType(SupplierPurchaseOrder.IntentType.INITIAL)
                .revision(0)
                .documentId(documentId)
                .activeIntentKey(activeKey)
                .attemptState(TransmissionAttemptState.CONFIRMED)
                .statusPollingActive(true)
                .lastPolledAt(lastPolledAt)
                .build();
    }

    @Test
    void refusesASecondActiveIntentForTheSameTuple() {
        // The duplicate-order constraint, at the level that actually enforces it. Two instances
        // racing on the same command both pass the application's lookup; only one gets a row.
        intentRepository.saveAndFlush(intent("DUR00000000000000000000000000000001", "claimed", null));

        assertThatThrownBy(() ->
                        intentRepository.saveAndFlush(intent("DUR00000000000000000000000000000002", "claimed", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsASecondIntentOnceTheFirstReleasesItsClaim() {
        // A refused or operator-terminated intent clears its key, and re-ordering is then
        // legitimate -- under a new intent with a new document id.
        intentRepository.saveAndFlush(intent("DUR00000000000000000000000000000003", null, null));

        assertThat(intentRepository.saveAndFlush(intent("DUR00000000000000000000000000000004", "claimed-again", null)))
                .isNotNull();
    }

    @Test
    void refusesTwoIntentsSharingAWireDocumentId() {
        intentRepository.saveAndFlush(intent("DUR00000000000000000000000000000005", "key-a", null));

        assertThatThrownBy(() ->
                        intentRepository.saveAndFlush(intent("DUR00000000000000000000000000000005", "key-b", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pollsNeverAskedOrdersBeforeOnesAlreadyBeingTracked() {
        // The bug this query's explicit `nulls first` exists to prevent. A newly confirmed order
        // has no lastPolledAt, and PostgreSQL sorts nulls LAST for ASC -- which would put every
        // brand-new order at the back of the queue behind orders already being tracked, exactly
        // backwards. Asserted here because it is dialect behaviour, not application logic.
        intentRepository.saveAndFlush(
                intent("DUR00000000000000000000000000000006", "poll-a", Instant.parse("2026-08-14T09:00:00Z")));
        intentRepository.saveAndFlush(
                intent("DUR00000000000000000000000000000007", "poll-b", Instant.parse("2026-08-14T08:00:00Z")));
        intentRepository.saveAndFlush(intent("DUR00000000000000000000000000000008", "poll-c", null));

        assertThat(intentRepository.findDueForStatusPolling(PageRequest.of(0, 10)))
                .extracting(SupplierTransmissionIntentEntity::getDocumentId)
                .containsExactly(
                        "DUR00000000000000000000000000000008",
                        "DUR00000000000000000000000000000007",
                        "DUR00000000000000000000000000000006");
    }

    @Test
    void offersNothingToPollOncePollingIsSwitchedOff() {
        SupplierTransmissionIntentEntity finished =
                intent("DUR00000000000000000000000000000009", "poll-d", Instant.parse("2026-08-14T09:00:00Z"));
        finished.setStatusPollingActive(false);
        intentRepository.saveAndFlush(finished);

        assertThat(intentRepository.findDueForStatusPolling(PageRequest.of(0, 10)))
                .extracting(SupplierTransmissionIntentEntity::getDocumentId)
                .doesNotContain("DUR00000000000000000000000000000009");
    }

    /**
     * The cross-purchase-order ledger search (issue #1638 decision 6). Exercised against the real
     * schema because every one of its behaviours is a query behaviour: which predicate a null
     * parameter switches off, how the window's half-open bounds land on {@code createdAt}, and the
     * newest-first ordering the worklist promises.
     */
    @Nested
    @DisplayName("search across purchase orders")
    class SearchAcrossPurchaseOrders {

        private static final UUID OTHER_PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");

        /**
         * Any placeholder compared against null, however Hibernate happens to lay the statement
         * out: {@code DOTALL} so the run of whitespace can be a newline under formatted SQL, and
         * case-insensitive because the keywords are the dialect's to capitalise, not ours.
         */
        private static final String UNTYPED_NULL_CHECK = "(?si).*\\?\\s*is\\s+null.*";

        private int sequence = 0;

        private SupplierTransmissionIntentEntity searchable(
                TransmissionAttemptState state,
                UUID vendorProfileId,
                String purchaseOrderNumber,
                String supplierOrderNumber,
                Instant createdAt) {
            String documentId = "DURSEARCH%026d".formatted(++sequence);
            SupplierTransmissionIntentEntity row = SupplierTransmissionIntentEntity.builder()
                    .vendorProfileId(vendorProfileId)
                    .supplierRef("michelin-eu")
                    .purchaseOrderId(UUID.randomUUID())
                    .purchaseOrderNumber(purchaseOrderNumber)
                    .supplierOrderNumber(supplierOrderNumber)
                    .intentType(SupplierPurchaseOrder.IntentType.INITIAL)
                    .revision(0)
                    .documentId(documentId)
                    .attemptState(state)
                    .build();
            intentRepository.saveAndFlush(row);
            // JPA auditing stamps createdAt from the clock on persist, so a controlled history has
            // to be written underneath it; the context is then cleared so the query rereads rows.
            entityManager
                    .createNativeQuery("UPDATE supplier_transmission_intent SET created_at = :createdAt"
                            + " WHERE document_id = :documentId")
                    .setParameter("createdAt", createdAt)
                    .setParameter("documentId", documentId)
                    .executeUpdate();
            entityManager.clear();
            return row;
        }

        private Page<SupplierTransmissionIntentEntity> search(
                TransmissionAttemptState state, UUID vendorProfileId, String pattern, Instant from, Instant to) {
            return intentRepository.search(state, vendorProfileId, pattern, from, to, PageRequest.of(0, 10));
        }

        @Test
        void filtersTheManualReviewQueue() {
            // The filter the whole surface exists for: the rows waiting on a human.
            searchable(TransmissionAttemptState.MANUAL_REVIEW, PROFILE_ID, "PO-1", null, Instant.now());
            searchable(TransmissionAttemptState.CONFIRMED, PROFILE_ID, "PO-2", null, Instant.now());
            searchable(TransmissionAttemptState.PENDING, PROFILE_ID, "PO-3", null, Instant.now());

            assertThat(search(TransmissionAttemptState.MANUAL_REVIEW, null, null, null, null))
                    .extracting(SupplierTransmissionIntentEntity::getPurchaseOrderNumber)
                    .containsExactly("PO-1");
        }

        @Test
        void filtersByVendorProfile() {
            searchable(TransmissionAttemptState.CONFIRMED, PROFILE_ID, "PO-OURS", null, Instant.now());
            searchable(TransmissionAttemptState.CONFIRMED, OTHER_PROFILE_ID, "PO-THEIRS", null, Instant.now());

            assertThat(search(null, OTHER_PROFILE_ID, null, null, null))
                    .extracting(SupplierTransmissionIntentEntity::getPurchaseOrderNumber)
                    .containsExactly("PO-THEIRS");
        }

        @Test
        void matchesEitherSideOfThePhoneCallCaseInsensitively() {
            // The buyer quotes the purchase-order number; the vendor quotes its own. Either must
            // find the row.
            searchable(TransmissionAttemptState.CONFIRMED, PROFILE_ID, "PO-4471", null, Instant.now());
            searchable(TransmissionAttemptState.CONFIRMED, PROFILE_ID, "PO-9999", "MICH-770412", Instant.now());

            assertThat(search(null, null, "%po-44%", null, null))
                    .extracting(SupplierTransmissionIntentEntity::getPurchaseOrderNumber)
                    .containsExactly("PO-4471");
            assertThat(search(null, null, "%mich-77%", null, null))
                    .extracting(SupplierTransmissionIntentEntity::getSupplierOrderNumber)
                    .containsExactly("MICH-770412");
        }

        @Test
        void boundsTheWindowHalfOpenOnCreatedAt() {
            searchable(
                    TransmissionAttemptState.CONFIRMED,
                    PROFILE_ID,
                    "PO-BEFORE",
                    null,
                    Instant.parse("2026-08-09T23:59:59Z"));
            searchable(
                    TransmissionAttemptState.CONFIRMED,
                    PROFILE_ID,
                    "PO-AT-FROM",
                    null,
                    Instant.parse("2026-08-10T00:00:00Z"));
            searchable(
                    TransmissionAttemptState.CONFIRMED,
                    PROFILE_ID,
                    "PO-INSIDE",
                    null,
                    Instant.parse("2026-08-10T12:00:00Z"));
            searchable(
                    TransmissionAttemptState.CONFIRMED,
                    PROFILE_ID,
                    "PO-AT-TO",
                    null,
                    Instant.parse("2026-08-11T00:00:00Z"));

            assertThat(search(
                            null,
                            null,
                            null,
                            Instant.parse("2026-08-10T00:00:00Z"),
                            Instant.parse("2026-08-11T00:00:00Z")))
                    .extracting(SupplierTransmissionIntentEntity::getPurchaseOrderNumber)
                    // from is inclusive, to is exclusive: adjacent windows tile without listing a
                    // boundary intent twice.
                    .containsExactlyInAnyOrder("PO-AT-FROM", "PO-INSIDE");
        }

        @Test
        void listsNewestFirstAndPages() {
            searchable(
                    TransmissionAttemptState.CONFIRMED,
                    PROFILE_ID,
                    "PO-OLDEST",
                    null,
                    Instant.parse("2026-08-01T00:00:00Z"));
            searchable(
                    TransmissionAttemptState.CONFIRMED,
                    PROFILE_ID,
                    "PO-NEWEST",
                    null,
                    Instant.parse("2026-08-03T00:00:00Z"));
            searchable(
                    TransmissionAttemptState.CONFIRMED,
                    PROFILE_ID,
                    "PO-MIDDLE",
                    null,
                    Instant.parse("2026-08-02T00:00:00Z"));

            Page<SupplierTransmissionIntentEntity> firstPage =
                    intentRepository.search(null, null, null, null, null, PageRequest.of(0, 2));

            assertThat(firstPage.getTotalElements()).isEqualTo(3);
            assertThat(firstPage.getContent())
                    .extracting(SupplierTransmissionIntentEntity::getPurchaseOrderNumber)
                    .containsExactly("PO-NEWEST", "PO-MIDDLE");
            assertThat(intentRepository.search(null, null, null, null, null, PageRequest.of(1, 2)))
                    .extracting(SupplierTransmissionIntentEntity::getPurchaseOrderNumber)
                    .containsExactly("PO-OLDEST");
        }

        @Test
        void emitsNoUntypedPlaceholderForAnAbsentFilter() {
            // The regression guard for issue #1891, and the one assertion here that H2 cannot make
            // on its own. Written as `(:param IS NULL OR column = :param)`, this search emitted a
            // bare `? is null`, which H2 accepts and PostgreSQL rejects at parse time -- before any
            // value is bound, so every call to the endpoint was a 500 while this test class stayed
            // green. CI has no PostgreSQL to fail on it, so what is asserted instead is the shape
            // of the SQL: an absent filter must contribute no placeholder at all.
            CapturedSql.clear();

            intentRepository.search(null, null, null, null, null, PageRequest.of(0, 10));

            assertThat(CapturedSql.statements())
                    .isNotEmpty()
                    .allSatisfy(sql -> assertThat(sql).doesNotMatch(UNTYPED_NULL_CHECK));
        }
    }

    /**
     * Records the SQL Hibernate actually emits, so {@code emitsNoUntypedPlaceholderForAnAbsentFilter}
     * can assert on its shape. Registered through {@code hibernate.session_factory.statement_inspector},
     * which instantiates it by name -- hence public, static and no-arg.
     */
    public static final class CapturedSql implements StatementInspector {

        private static final List<String> STATEMENTS = Collections.synchronizedList(new ArrayList<>());

        static void clear() {
            STATEMENTS.clear();
        }

        static List<String> statements() {
            return List.copyOf(STATEMENTS);
        }

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql);
            return sql;
        }
    }
}
