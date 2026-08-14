package com.positivity.supplier.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder;
import com.positivity.supplier.internal.entity.SupplierTransmissionIntentEntity;
import com.positivity.supplier.internal.enums.TransmissionAttemptState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@DisplayName("Transmission ledger persistence (ADR-0052, #1226/#1318)")
class SupplierTransmissionIntentRepositoryTest {

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID PURCHASE_ORDER_ID = UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-000000000001");

    @Autowired
    private SupplierTransmissionIntentRepository intentRepository;

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
}
