package com.positivity.supplier.internal.adapter.ediwheelc1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierDeliverySchedule;
import com.positivity.supplier.internal.domain.model.SupplierOrderResult;
import com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EDIWheel Order Creation C1.0 codec (#1226)")
class EdiwheelC10OrderCodecTest {

    private static final String DOCUMENT_ID = "DUR0198F3A24C7E7A1B9C2D3E4F5A6B7C8D";

    private final EdiwheelC10OrderCodec codec = new EdiwheelC10OrderCodec();

    private static String fixture(String name) throws IOException {
        try (InputStream in = EdiwheelC10OrderCodecTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in)
                    .as("golden fixture %s must be on the test classpath", name)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static SupplierPurchaseOrder order() {
        return new SupplierPurchaseOrder(
                UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-3e4f5a6b7c8d"),
                UUID.fromString("0198f3a2-4c7e-7a1b-9c2d-000000000001"),
                SupplierPurchaseOrder.IntentType.INITIAL,
                0,
                DOCUMENT_ID,
                "PO-4471",
                List.of(
                        new SupplierPurchaseOrder.Line(1, "3528709999083", "999908", 12, LocalDate.of(2026, 8, 10)),
                        new SupplierPurchaseOrder.Line(2, "3528709999084", null, 10, null)));
    }

    @Test
    void registersUnderTheOrderCreateTriple() {
        assertThat(codec.capability()).isEqualTo(SupplierCapability.ORDER_CREATE);
        assertThat(codec.family()).isEqualTo(ProtocolFamily.EDIWHEEL_C1);
        assertThat(codec.version()).isEqualTo(ProtocolVersion.C1_0);
    }

    @Nested
    @DisplayName("encodeOrder")
    class EncodeOrder {

        @Test
        void carriesTheIntentDerivedDocumentIdAsCustomerReference() {
            String body = codec.encodeOrder(order(), new PartyContext("30012456", "91", null), null, null);

            assertThat(body).contains("<ew:CustomerReference>").contains(DOCUMENT_ID);
        }

        @Test
        void producesTheSameDocumentEveryTimeForOneIntent() {
            // The retry guarantee, stated as a test: the same intent always presents the same
            // reference, so a vendor's deduplication has something to work with and a later status
            // query can still find the order.
            PartyContext party = new PartyContext("30012456", "91", null);

            assertThat(codec.encodeOrder(order(), party, null, null))
                    .isEqualTo(codec.encodeOrder(order(), party, null, null));
        }

        @Test
        void sendsTheConsigneeWhenTheOrderHasADeliveryAccount() {
            String body =
                    codec.encodeOrder(order(), new PartyContext("30012456", "91", UUID.randomUUID()), "30012999", "91");

            assertThat(body).contains("<ew:Consignee>").contains("30012999");
        }

        @Test
        void omitsTheConsigneeWhenTheProfileAddressesNone() {
            String body = codec.encodeOrder(order(), new PartyContext("30012456", "91", null), null, null);

            assertThat(body).doesNotContain("<ew:Consignee>");
        }

        @Test
        void sendsRequestedDeliveryDatesOnlyWhereTheOrderStatesOne() {
            String body = codec.encodeOrder(order(), new PartyContext("30012456", "91", null), null, null);

            // One line asks for a date, the other does not. An absent request is not a request for
            // today, so nothing is invented for line 2.
            assertThat(body).containsOnlyOnce("<ew:RequestedDeliveryDate>2026-08-10</ew:RequestedDeliveryDate>");
            assertThat(body.split("RequestedDeliveryDate", -1)).hasSize(3);
        }

        @Test
        void zeroPadsLineIdsToTheNormsWidth() {
            String body = codec.encodeOrder(order(), new PartyContext("30012456", "91", null), null, null);

            assertThat(body).contains("<ew:LineID>000001</ew:LineID>").contains("<ew:LineID>000002</ew:LineID>");
        }

        @Test
        void failsBeforeTheNetworkWhenAPartyHasNoAgencyCode() {
            // Raised before anything is sent, so the attempt never leaves PENDING and a later
            // re-dispatch after the configuration fix is safe (ADR-0052 §2).
            assertThatThrownBy(() -> codec.encodeOrder(order(), new PartyContext("30012456", null, null), null, null))
                    .isInstanceOf(OrderEncodeException.class)
                    .hasMessageContaining("billing account");
        }

        @Test
        void failsWhenADeliveryAccountHasNoAgencyCode() {
            assertThatThrownBy(() -> codec.encodeOrder(
                            order(), new PartyContext("30012456", "91", UUID.randomUUID()), "30012999", " "))
                    .isInstanceOf(OrderEncodeException.class)
                    .hasMessageContaining("delivery account");
        }

        @Test
        void isNeverIdempotent() {
            // The one call in this module where an automatic retry after transmission puts real
            // tyres on a real truck twice.
            SupplierRequestSpec spec =
                    codec.buildRequest(order(), new PartyContext("30012456", "91", null), null, null);

            assertThat(spec.method()).isEqualTo("POST");
            assertThat(spec.idempotent()).isFalse();
        }
    }

    @Nested
    @DisplayName("decodeResponse")
    class DecodeResponse {

        @Test
        void readsAConfirmationWithItsVendorOrderNumber() throws IOException {
            SupplierOrderResult result = codec.decodeResponse(fixture("order-response-c10-partial.xml"));

            assertThat(result.status()).isEqualTo(SupplierOrderResult.Status.CONFIRMED);
            assertThat(result.supplierOrderNumber()).isEqualTo("MICH-770412");
            assertThat(result.lines()).hasSize(2);
        }

        @Test
        void representsPartialAcceptancePerLine() throws IOException {
            SupplierOrderResult.Line partial = codec.decodeResponse(fixture("order-response-c10-partial.xml"))
                    .lines()
                    .get(1);

            assertThat(partial.orderedQuantity()).isEqualTo(10);
            assertThat(partial.confirmedQuantity()).isEqualTo(6);
            assertThat(partial.killedQuantity()).isEqualTo(4);
            assertThat(partial.vendorErrorText()).isEqualTo("Partially available");
        }

        @Test
        void keepsEveryConfirmedScheduleOfALine() throws IOException {
            SupplierOrderResult.Line split = codec.decodeResponse(fixture("order-response-c10-partial.xml"))
                    .lines()
                    .getFirst();

            assertThat(split.schedules())
                    .extracting(SupplierDeliverySchedule::deliveryDate)
                    .containsExactly(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 26));
            assertThat(split.confirmedQuantity()).isEqualTo(12);
        }

        @Test
        void readsADocumentLevelErrorAsARejection() throws IOException {
            SupplierOrderResult result = codec.decodeResponse(fixture("order-response-c10-rejected.xml"));

            assertThat(result.status()).isEqualTo(SupplierOrderResult.Status.REJECTED);
            assertThat(result.vendorErrorCode()).isEqualTo("913");
            assertThat(result.vendorReason()).isEqualTo("Buyer party unknown");
        }

        @Test
        void rejectsAnEmptyBody() {
            assertThatThrownBy(() -> codec.decodeResponse(null)).isInstanceOf(OrderDecodeException.class);
        }
    }
}
