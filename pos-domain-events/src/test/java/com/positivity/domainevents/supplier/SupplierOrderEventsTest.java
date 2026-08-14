package com.positivity.domainevents.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The supplier order-lifecycle contract (ADR-0049 §3; CAP-320 #1226, #1318).
 *
 * <p>These payloads are what other domains compile against, so the properties asserted here are
 * contract, not implementation: the tree survives, absence stays absent, and the distinctions a
 * consumer needs in order to be safe — manual versus vendor-sourced, not-received versus
 * cancelled — remain visible.
 */
@DisplayName("Supplier order lifecycle events (#1226, #1318)")
class SupplierOrderEventsTest {

    private static final UUID INTENT_ID = UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-3e4f5a6b7c8d");
    private static final UUID PURCHASE_ORDER_ID = UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-000000000001");
    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final String DOCUMENT_ID = "DUR0198F3A24C7E7A1B9C2D3E4F5A6B7C8D";
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-14T12:00:00Z");

    private static SupplierOrderStatusDespatch despatch(String asn, Integer quantity) {
        return new SupplierOrderStatusDespatch(LocalDate.of(2026, 8, 11), asn, "000001", quantity);
    }

    private static SupplierOrderStatusSchedule schedule(SupplierOrderStatusDespatch... despatches) {
        return new SupplierOrderStatusSchedule(
                LocalDate.of(2026, 8, 12), 10, null, 4, null, null, 6, List.of(despatches));
    }

    private static SupplierOrderStatusLine line(SupplierOrderStatusSchedule... schedules) {
        return new SupplierOrderStatusLine(
                "000001",
                "000001",
                "3528709999083",
                "999908",
                "BUY-1",
                "255/55R19",
                10,
                LocalDate.of(2026, 8, 10),
                List.of(schedules),
                null,
                null);
    }

    private static SupplierOrderStatusChangedV1 statusChanged(SupplierOrderStatusLine... lines) {
        return new SupplierOrderStatusChangedV1(
                INTENT_ID,
                PURCHASE_ORDER_ID,
                PROFILE_ID,
                "michelin-eu",
                DOCUMENT_ID,
                "MICH-770412",
                SupplierOrderStatusChangedV1.Status.CONFIRMED,
                null,
                OBSERVED_AT,
                List.of(lines));
    }

    @Nested
    @DisplayName("supplier.orderstatus.changed")
    class StatusChanged {

        @Test
        void publishesUnderTheContractedEventTypeAndVersion() {
            assertThat(SupplierOrderStatusChangedV1.EVENT_TYPE).isEqualTo("supplier.orderstatus.changed");
            assertThat(SupplierOrderStatusChangedV1.SCHEMA_VERSION).isEqualTo(1);
        }

        @Test
        void carriesTheWholeLineScheduleDespatchTree() {
            SupplierOrderStatusChangedV1 event = statusChanged(line(schedule(despatch("ASN-1", 6))));

            assertThat(event.lines()).hasSize(1);
            assertThat(event.lines().getFirst().schedules()).hasSize(1);
            assertThat(event.lines().getFirst().schedules().getFirst().despatches())
                    .hasSize(1);
        }

        @Test
        void reportsWhetherTheVendorHasScheduledOrDespatchedAnything() {
            assertThat(line(schedule(despatch("ASN-1", 6))).hasConfirmedSchedule())
                    .isTrue();
            assertThat(line(schedule(despatch("ASN-1", 6))).hasDespatched()).isTrue();

            // A line the vendor holds but has not scheduled. A meaningful state, not a gap: a
            // consumer should render "no date yet" rather than falling back to the requested date.
            assertThat(line().hasConfirmedSchedule()).isFalse();
            assertThat(line().hasDespatched()).isFalse();
            assertThat(line(schedule()).hasDespatched()).isFalse();
            assertThat(schedule().hasDespatched()).isFalse();
        }

        @Test
        void keepsTheThreeDateKindsInSeparateFields() {
            SupplierOrderStatusLine subject = line(schedule(despatch("ASN-1", 6)));

            assertThat(subject.requestedDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 10));
            assertThat(subject.schedules().getFirst().deliveryDate()).isEqualTo(LocalDate.of(2026, 8, 12));
            assertThat(subject.schedules().getFirst().despatches().getFirst().despatchDate())
                    .isEqualTo(LocalDate.of(2026, 8, 11));
        }

        @Test
        void reportsWhetherADespatchCarriesAnAsnHandleAnOperatorCouldQuote() {
            assertThat(despatch("ASN-2026-08-11-0001", 6).hasDespatchAdviceReference())
                    .isTrue();
            assertThat(despatch(null, 6).hasDespatchAdviceReference()).isFalse();
            assertThat(despatch("  ", 6).hasDespatchAdviceReference()).isFalse();
        }

        @Test
        void offersADespatchShapeForConsumersBuildingExpectations() {
            SupplierOrderStatusDespatch minimal = SupplierOrderStatusDespatch.of(LocalDate.of(2026, 8, 11), 6);

            assertThat(minimal.despatchAdviceDocumentId()).isNull();
            assertThat(minimal.hasDespatchAdviceReference()).isFalse();
            assertThat(minimal.despatchedQuantity()).isEqualTo(6);
        }

        @Test
        void cannotBeMutatedThroughTheListItWasBuiltFrom() {
            List<SupplierOrderStatusLine> lines = new ArrayList<>(List.of(line()));
            SupplierOrderStatusChangedV1 event = statusChanged(line());
            lines.clear();

            assertThat(event.lines()).hasSize(1);
            assertThatThrownBy(() -> event.lines().add(line())).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void refusesToBeBuiltWithoutTheIdentityAConsumerNeeds() {
            assertThatThrownBy(() -> new SupplierOrderStatusChangedV1(
                            null,
                            PURCHASE_ORDER_ID,
                            PROFILE_ID,
                            "michelin-eu",
                            DOCUMENT_ID,
                            null,
                            SupplierOrderStatusChangedV1.Status.CONFIRMED,
                            null,
                            OBSERVED_AT,
                            List.of()))
                    .isInstanceOf(NullPointerException.class);

            // Without a purchase order id a consumer cannot place the fact on any timeline.
            assertThatThrownBy(() -> new SupplierOrderStatusChangedV1(
                            INTENT_ID,
                            null,
                            PROFILE_ID,
                            "michelin-eu",
                            DOCUMENT_ID,
                            null,
                            SupplierOrderStatusChangedV1.Status.CONFIRMED,
                            null,
                            OBSERVED_AT,
                            List.of()))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new SupplierOrderStatusChangedV1(
                            INTENT_ID,
                            PURCHASE_ORDER_ID,
                            null,
                            "michelin-eu",
                            DOCUMENT_ID,
                            null,
                            SupplierOrderStatusChangedV1.Status.CONFIRMED,
                            null,
                            OBSERVED_AT,
                            List.of()))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new SupplierOrderStatusChangedV1(
                            INTENT_ID,
                            PURCHASE_ORDER_ID,
                            PROFILE_ID,
                            null,
                            DOCUMENT_ID,
                            null,
                            SupplierOrderStatusChangedV1.Status.CONFIRMED,
                            null,
                            OBSERVED_AT,
                            List.of()))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new SupplierOrderStatusChangedV1(
                            INTENT_ID,
                            PURCHASE_ORDER_ID,
                            PROFILE_ID,
                            "michelin-eu",
                            null,
                            null,
                            SupplierOrderStatusChangedV1.Status.CONFIRMED,
                            null,
                            OBSERVED_AT,
                            List.of()))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new SupplierOrderStatusChangedV1(
                            INTENT_ID,
                            PURCHASE_ORDER_ID,
                            PROFILE_ID,
                            "michelin-eu",
                            DOCUMENT_ID,
                            null,
                            null,
                            null,
                            OBSERVED_AT,
                            List.of()))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new SupplierOrderStatusChangedV1(
                            INTENT_ID,
                            PURCHASE_ORDER_ID,
                            PROFILE_ID,
                            "michelin-eu",
                            DOCUMENT_ID,
                            null,
                            SupplierOrderStatusChangedV1.Status.CONFIRMED,
                            null,
                            null,
                            List.of()))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new SupplierOrderStatusChangedV1(
                            INTENT_ID,
                            PURCHASE_ORDER_ID,
                            PROFILE_ID,
                            "michelin-eu",
                            DOCUMENT_ID,
                            null,
                            SupplierOrderStatusChangedV1.Status.CONFIRMED,
                            null,
                            OBSERVED_AT,
                            null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void requiresEveryNestedLevelToStateItsChildren() {
            assertThatThrownBy(() -> new SupplierOrderStatusSchedule(null, null, null, null, null, null, null, null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SupplierOrderStatusLine(
                            null, null, null, null, null, null, null, null, null, null, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("supplier.order.requested")
    class OrderRequested {

        private static SupplierOrderRequestedV1 command(List<SupplierOrderRequestedLine> lines) {
            return new SupplierOrderRequestedV1(
                    PURCHASE_ORDER_ID,
                    0,
                    SupplierOrderRequestedV1.IntentType.INITIAL,
                    "michelin-eu",
                    null,
                    "PO-4471",
                    lines);
        }

        @Test
        void publishesUnderTheContractedEventTypeAndVersion() {
            assertThat(SupplierOrderRequestedV1.EVENT_TYPE).isEqualTo("supplier.order.requested");
            assertThat(SupplierOrderRequestedV1.SCHEMA_VERSION).isEqualTo(1);
        }

        @Test
        void carriesTheProducersStatementOfWhyThisTransmissionExists() {
            // pos-supplier cannot tell a revision from a redelivery by looking at the payload, so
            // the producer says which it is and the intent uniqueness tuple uses it.
            assertThat(SupplierOrderRequestedV1.IntentType.values())
                    .containsExactly(
                            SupplierOrderRequestedV1.IntentType.INITIAL,
                            SupplierOrderRequestedV1.IntentType.REVISION,
                            SupplierOrderRequestedV1.IntentType.REPLACEMENT,
                            SupplierOrderRequestedV1.IntentType.SPLIT);
        }

        @Test
        void carriesTheRequestedDeliveryDatePerLine() {
            SupplierOrderRequestedLine line =
                    new SupplierOrderRequestedLine(1, "3528709999083", "999908", 4, LocalDate.of(2026, 8, 10));

            assertThat(command(List.of(line)).lines().getFirst().requestedDeliveryDate())
                    .isEqualTo(LocalDate.of(2026, 8, 10));
        }

        @Test
        void refusesToBeBuiltWithoutTheFieldsThatIdentifyTheOrder() {
            assertThatThrownBy(() -> new SupplierOrderRequestedV1(
                            null, 0, SupplierOrderRequestedV1.IntentType.INITIAL, "michelin-eu", null, null, List.of()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SupplierOrderRequestedV1(
                            PURCHASE_ORDER_ID, 0, null, "michelin-eu", null, null, List.of()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SupplierOrderRequestedV1(
                            PURCHASE_ORDER_ID,
                            0,
                            SupplierOrderRequestedV1.IntentType.INITIAL,
                            null,
                            null,
                            null,
                            List.of()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SupplierOrderRequestedV1(
                            PURCHASE_ORDER_ID,
                            0,
                            SupplierOrderRequestedV1.IntentType.INITIAL,
                            "michelin-eu",
                            null,
                            null,
                            null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("supplier.order.confirmed / supplier.order.rejected")
    class OrderResults {

        private static SupplierOrderConfirmedLine confirmedLine(Integer ordered, Integer confirmed) {
            return new SupplierOrderConfirmedLine(
                    "000001", "3528709999083", "999908", ordered, confirmed, null, List.of(), null, null);
        }

        @Test
        void publishUnderTheContractedEventTypesAndVersions() {
            assertThat(SupplierOrderConfirmedV1.EVENT_TYPE).isEqualTo("supplier.order.confirmed");
            assertThat(SupplierOrderConfirmedV1.SCHEMA_VERSION).isEqualTo(1);
            assertThat(SupplierOrderRejectedV1.EVENT_TYPE).isEqualTo("supplier.order.rejected");
            assertThat(SupplierOrderRejectedV1.SCHEMA_VERSION).isEqualTo(1);
        }

        @Test
        void showsWhetherAConfirmationCameFromTheVendorOrFromAHuman() {
            // A timeline that showed these identically would hide which orders someone vouched for.
            assertThat(SupplierOrderConfirmedV1.Source.values())
                    .containsExactly(
                            SupplierOrderConfirmedV1.Source.ORDER_RESPONSE,
                            SupplierOrderConfirmedV1.Source.STATUS_RECONCILIATION,
                            SupplierOrderConfirmedV1.Source.MANUAL_RESOLUTION);
        }

        @Test
        void distinguishesTheThreeWaysATransmissionCanEndWithoutTheVendorHoldingTheOrder() {
            assertThat(SupplierOrderRejectedV1.Reason.values())
                    .containsExactly(
                            SupplierOrderRejectedV1.Reason.VENDOR_REJECTED,
                            SupplierOrderRejectedV1.Reason.MANUAL_NOT_RECEIVED,
                            SupplierOrderRejectedV1.Reason.MANUAL_CANCELLED);
        }

        @Test
        void reportsAShortfallOnlyWhenBothQuantitiesAreStated() {
            assertThat(confirmedLine(10, 6).isPartiallyAccepted()).isTrue();
            assertThat(confirmedLine(10, 10).isPartiallyAccepted()).isFalse();
            // An unknown is not a shortfall: a consumer treating it as one would raise a backorder
            // against a vendor's silence.
            assertThat(confirmedLine(10, null).isPartiallyAccepted()).isFalse();
            assertThat(confirmedLine(null, 6).isPartiallyAccepted()).isFalse();
        }

        @Test
        void refuseToBeBuiltWithoutTheirIdentity() {
            Instant now = OBSERVED_AT;
            assertThatThrownBy(() -> new SupplierOrderConfirmedV1(
                            null,
                            PURCHASE_ORDER_ID,
                            PROFILE_ID,
                            "michelin-eu",
                            DOCUMENT_ID,
                            null,
                            SupplierOrderConfirmedV1.Source.ORDER_RESPONSE,
                            now,
                            List.of()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SupplierOrderConfirmedV1(
                            INTENT_ID,
                            PURCHASE_ORDER_ID,
                            PROFILE_ID,
                            "michelin-eu",
                            DOCUMENT_ID,
                            null,
                            null,
                            now,
                            List.of()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SupplierOrderRejectedV1(
                            INTENT_ID,
                            PURCHASE_ORDER_ID,
                            PROFILE_ID,
                            "michelin-eu",
                            DOCUMENT_ID,
                            null,
                            null,
                            null,
                            now,
                            List.of()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SupplierOrderRejectedV1(
                            INTENT_ID,
                            PURCHASE_ORDER_ID,
                            PROFILE_ID,
                            "michelin-eu",
                            DOCUMENT_ID,
                            SupplierOrderRejectedV1.Reason.VENDOR_REJECTED,
                            null,
                            null,
                            now,
                            null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void areImmutableOnceBuilt() {
            SupplierOrderConfirmedV1 confirmed = new SupplierOrderConfirmedV1(
                    INTENT_ID,
                    PURCHASE_ORDER_ID,
                    PROFILE_ID,
                    "michelin-eu",
                    DOCUMENT_ID,
                    "MICH-1",
                    SupplierOrderConfirmedV1.Source.ORDER_RESPONSE,
                    OBSERVED_AT,
                    List.of(confirmedLine(10, 10)));

            assertThatThrownBy(() -> confirmed.lines().add(confirmedLine(1, 1)))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> confirmed.lines().getFirst().schedules().add(schedule()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
