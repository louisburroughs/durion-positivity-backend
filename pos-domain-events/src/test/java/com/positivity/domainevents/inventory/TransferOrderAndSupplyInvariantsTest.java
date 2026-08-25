package com.positivity.domainevents.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the two inventory facts whose compact constructors enforce a
 * relationship <em>between</em> components, and which
 * {@code DomainEventContractTest} therefore cannot cover with generic sample
 * values.
 *
 * <p>
 * Both were at 0% and both are the largest uncovered records in the module.
 * Their validation is not the usual null sweep — it is a small state machine
 * expressed in a constructor, and getting it wrong publishes a fact that
 * consumers will act on.
 */
@DisplayName("Inventory facts with cross-field invariants")
class TransferOrderAndSupplyInvariantsTest {

    private static final UUID ID = UUID.fromString("01980001-0000-7000-8000-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("01980001-0000-7000-8000-000000000002");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-12T12:00:00Z");

    @Nested
    @DisplayName("ExpectedSupplyDroppedV1")
    class ExpectedSupplyDropped {

        private ExpectedSupplyDroppedV1 fact(String reason, BigDecimal quantity) {
            return new ExpectedSupplyDroppedV1("OIL-5W30", ID, quantity, OTHER_ID, reason, OCCURRED_AT);
        }

        @Test
        @DisplayName("carries its schema identity")
        void schemaIdentity() {
            assertThat(ExpectedSupplyDroppedV1.EVENT_TYPE).isEqualTo("inventory.expected-supply.dropped");
            assertThat(ExpectedSupplyDroppedV1.SCHEMA_VERSION).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"CLOSED", "CANCELLED"})
        @DisplayName("accepts only the two reasons a purchase order can stop supplying stock")
        void allowedReasons(String reason) {
            assertThat(fact(reason, BigDecimal.TEN).reason()).isEqualTo(reason);
        }

        @ParameterizedTest
        @ValueSource(strings = {"closed", "CLOSED ", "SHORT_CLOSED", "OTHER", ""})
        @DisplayName("rejects any other reason rather than passing it through")
        void rejectedReasons(String reason) {
            // This fact tells planning to stop expecting stock that a purchase order was going
            // to supply. The reason drives whether the shortfall is re-ordered or written off,
            // so an unrecognised value must not reach a consumer that will branch on it — and
            // the match is exact, so lowercase and a trailing space are both rejected.
            assertThatThrownBy(() -> fact(reason, BigDecimal.TEN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reason must be one of");
        }

        @ParameterizedTest
        @CsvSource({"0", "-1"})
        @DisplayName("rejects a non-positive dropped quantity")
        void nonPositiveQuantityRejected(String quantity) {
            // Dropping zero units is not an event, and dropping a negative number would add
            // expected supply through a message that says it is removing some.
            assertThatThrownBy(() -> fact("CLOSED", new BigDecimal(quantity)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("droppedQuantity must be positive");
        }

        @Test
        @DisplayName("a blank sku is rejected but a null site is allowed")
        void skuRequiredSiteOptional() {
            assertThatThrownBy(() ->
                            new ExpectedSupplyDroppedV1("  ", ID, BigDecimal.ONE, OTHER_ID, "CLOSED", OCCURRED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sku must not be blank");

            // siteId is nullable: a purchase order can be closed before it has been allocated
            // to a receiving site.
            assertThat(new ExpectedSupplyDroppedV1("OIL-5W30", null, BigDecimal.ONE, OTHER_ID, "CLOSED", OCCURRED_AT)
                            .siteId())
                    .isNull();
        }
    }

    @Nested
    @DisplayName("TransferOrderUpdatedV1")
    class TransferOrderUpdated {

        private final List<TransferOrderUpdatedV1.LineSummary> lines =
                List.of(new TransferOrderUpdatedV1.LineSummary("SKU-1", 5, 5, 0));

        private TransferOrderUpdatedV1 fact(String status, String disposition) {
            return new TransferOrderUpdatedV1(ID, status, ID, null, OTHER_ID, null, lines, disposition, OCCURRED_AT);
        }

        @Test
        @DisplayName("carries its schema identity")
        void schemaIdentity() {
            assertThat(TransferOrderUpdatedV1.EVENT_TYPE).isEqualTo("inventory.transfer-order.updated");
            assertThat(TransferOrderUpdatedV1.SCHEMA_VERSION).isEqualTo(1);
        }

        @Test
        @DisplayName("a transfer with no lines is rejected")
        void emptyLinesRejected() {
            // A transfer order that moves nothing is not an update anyone can act on, and an
            // empty list would sail past a consumer that iterates it.
            assertThatThrownBy(() -> new TransferOrderUpdatedV1(
                            ID, "IN_TRANSIT", ID, null, OTHER_ID, null, List.of(), null, OCCURRED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lines must not be empty");
        }

        @Test
        @DisplayName("a null lines list is rejected, distinctly from an empty one")
        void nullLinesRejected() {
            // Same guard, the other branch: lines == null must be caught before isEmpty() would
            // NPE, so this is a decision, not an incidental crash.
            assertThatThrownBy(() -> new TransferOrderUpdatedV1(
                            ID, "IN_TRANSIT", ID, null, OTHER_ID, null, null, null, OCCURRED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lines must not be empty");
        }

        @Test
        @DisplayName("each required identity field is rejected when null")
        void requiredFieldsRejectedWhenNull() {
            // Every one of these guards fires before the fact ever reaches a consumer that
            // would dereference the missing value.
            assertThatThrownBy(() -> new TransferOrderUpdatedV1(
                            null, "IN_TRANSIT", ID, null, OTHER_ID, null, lines, null, OCCURRED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("transferOrderId must not be null");

            assertThatThrownBy(() -> new TransferOrderUpdatedV1(
                            ID, "IN_TRANSIT", null, null, OTHER_ID, null, lines, null, OCCURRED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceLocationId must not be null");

            assertThatThrownBy(() -> new TransferOrderUpdatedV1(
                            ID, "IN_TRANSIT", ID, null, null, null, lines, null, OCCURRED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("destinationLocationId must not be null");

            assertThatThrownBy(() ->
                            new TransferOrderUpdatedV1(ID, "IN_TRANSIT", ID, null, OTHER_ID, null, lines, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("occurredAt must not be null");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("a null or blank status is rejected")
        void statusRequired(String blankStatus) {
            assertThatThrownBy(() -> new TransferOrderUpdatedV1(
                            ID, blankStatus, ID, null, OTHER_ID, null, lines, null, OCCURRED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("status must not be blank");

            assertThatThrownBy(() ->
                            new TransferOrderUpdatedV1(ID, null, ID, null, OTHER_ID, null, lines, null, OCCURRED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("status must not be blank");
        }

        @ParameterizedTest
        @ValueSource(strings = {"LOST_IN_TRANSIT", "RETURNED_TO_SOURCE"})
        @DisplayName("SHORT_CLOSED accepts exactly the two dispositions that describe where the stock went")
        void allowedDispositions(String disposition) {
            assertThat(fact("SHORT_CLOSED", disposition).shortCloseDisposition())
                    .isEqualTo(disposition);
        }

        @Test
        @DisplayName("an unrecognised disposition is rejected even on a SHORT_CLOSED transfer")
        void unknownDispositionRejected() {
            // The two allowed values differ in who ends up holding the stock, which is the
            // whole point of recording it; a third value would leave that unresolved.
            assertThatThrownBy(() -> fact("SHORT_CLOSED", "WRITTEN_OFF"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("LOST_IN_TRANSIT or RETURNED_TO_SOURCE");
        }

        @Test
        @DisplayName("the status and disposition imply each other in both directions")
        void shortCloseInvariantIsBidirectional() {
            // Not one-directional, as it first appears from the disposition check alone: a
            // SHORT_CLOSED transfer must also say where the stock went. Closing a transfer short
            // without recording that leaves stock unaccounted for at both ends, which is the
            // outcome the disposition exists to prevent.
            assertThatThrownBy(() -> fact("SHORT_CLOSED", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires a shortCloseDisposition");

            assertThatThrownBy(() -> fact("IN_TRANSIT", "LOST_IN_TRANSIT"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only valid for status SHORT_CLOSED");

            // And an ordinary status with no disposition is fine.
            assertThat(fact("IN_TRANSIT", null).shortCloseDisposition()).isNull();
        }

        @Test
        @DisplayName("a line must name a sku and a positive requested quantity")
        void lineSummaryValidation() {
            assertThatThrownBy(() -> new TransferOrderUpdatedV1.LineSummary("  ", 5, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sku must not be blank");
            assertThatThrownBy(() -> new TransferOrderUpdatedV1.LineSummary("SKU-1", 0, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requestedQty must be positive");
            // Dispatched and received may be zero — a transfer that has been raised but not yet
            // picked is the normal starting state — but never negative, which would subtract
            // stock at the receiving end.
            assertThatThrownBy(() -> new TransferOrderUpdatedV1.LineSummary("SKU-1", 5, -1, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be negative");
            assertThat(new TransferOrderUpdatedV1.LineSummary("SKU-1", 5, 0, 0).receivedQty())
                    .isZero();
        }

        @Test
        @DisplayName("storage locations are optional on both ends")
        void storageLocationsAreOptional() {
            TransferOrderUpdatedV1 fact = fact("IN_TRANSIT", null);

            // A transfer between sites need not name a bin at either end.
            assertThat(fact.sourceStorageLocationId()).isNull();
            assertThat(fact.destinationStorageLocationId()).isNull();
        }
    }
}
