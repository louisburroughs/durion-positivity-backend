package com.positivity.supplier.internal.adapter.ediwheelc1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierDeliverySchedule;
import com.positivity.supplier.internal.domain.model.SupplierDespatch;
import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EDIWheel Order Status C1.1 codec (#1226, #1318)")
class EdiwheelC11OrderStatusCodecTest {

    /** The document id the golden fixtures echo back. */
    private static final String DOCUMENT_ID = "DUR0198F3A24C7E7A1B9C2D3E4F5A6B7C8D";

    private final EdiwheelC11OrderStatusCodec codec = new EdiwheelC11OrderStatusCodec();

    private static String fixture(String name) throws IOException {
        try (InputStream in = EdiwheelC11OrderStatusCodecTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in)
                    .as("golden fixture %s must be on the test classpath", name)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private SupplierOrderStatusResult decodeSample() throws IOException {
        return codec.decode(DOCUMENT_ID, fixture("order-status-c11-sample.xml")).result();
    }

    @Test
    void registersUnderTheOrderStatusTriple() {
        assertThat(codec.capability()).isEqualTo(SupplierCapability.ORDER_STATUS);
        assertThat(codec.family()).isEqualTo(ProtocolFamily.EDIWHEEL_C1);
        assertThat(codec.version()).isEqualTo(ProtocolVersion.C1_1);
    }

    @Nested
    @DisplayName("buildRequest")
    class BuildRequest {

        @Test
        void asksAboutOurOwnDocumentReference() {
            String body = codec.encodeRequest(DOCUMENT_ID, null, new PartyContext("30012456", "91", null));

            assertThat(body).contains("<ew:OrderReference>").contains(DOCUMENT_ID);
            assertThat(body).contains("<ew:PartyID>30012456</ew:PartyID>");
            assertThat(body).contains("<ew:AgencyCode>91</ew:AgencyCode>");
        }

        @Test
        void sendsTheVendorsOwnOrderNumberWhenKnown() {
            String body = codec.encodeRequest(DOCUMENT_ID, "MICH-770412", new PartyContext("30012456", "91", null));

            assertThat(body).contains("<ew:SupplierOrderNumber>").contains("MICH-770412");
        }

        @Test
        void asksForDeliveredOrdersToo() {
            // Indicator 2 = open, cancelled AND delivered. Anything narrower makes a completed order
            // vanish from the answer, and a vendor that stopped listing an order is indistinguishable
            // from a vendor that never had it.
            String body = codec.encodeRequest(DOCUMENT_ID, null, new PartyContext("30012456", "91", null));

            assertThat(body).contains("<ew:OrderStatusIndicator>2</ew:OrderStatusIndicator>");
        }

        @Test
        void isRetryableBecauseAStatusQueryCreatesNoVendorState() {
            SupplierRequestSpec spec = codec.buildRequest(DOCUMENT_ID, null, new PartyContext("30012456", "91", null));

            assertThat(spec.method()).isEqualTo("POST");
            assertThat(spec.idempotent()).isTrue();
            assertThat(spec.contentType()).isEqualTo("application/xml");
        }

        @Test
        void refusesToQueryWithoutAnAgencyCode() {
            assertThatThrownBy(() -> codec.encodeRequest(DOCUMENT_ID, null, new PartyContext("30012456", null, null)))
                    .isInstanceOf(OrderEncodeException.class)
                    .hasMessageContaining("agency code");
        }
    }

    @Nested
    @DisplayName("decode: the three date kinds stay distinct (#1318)")
    class ThreeDateKinds {

        @Test
        void keepsRequestedConfirmedAndDespatchedDatesApart() throws IOException {
            SupplierOrderStatusResult.Line line = decodeSample().lines().getFirst();

            // What we asked for.
            assertThat(line.requestedDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 10));
            // What the vendor committed to - two different dates, neither of them ours.
            assertThat(line.schedules())
                    .extracting(SupplierDeliverySchedule::deliveryDate)
                    .containsExactly(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 26));
            // When it actually left.
            assertThat(line.schedules().getFirst().despatches())
                    .extracting(SupplierDespatch::despatchDate)
                    .containsExactly(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 12));
        }

        @Test
        void doesNotFillAMissingConfirmedDateFromTheRequestedOne() throws IOException {
            // Line 3 has a requested date and no schedule at all. A codec that coalesced would show
            // receiving a vendor commitment for 2026-08-20 that the vendor never made.
            SupplierOrderStatusResult.Line unscheduled = decodeSample().lines().get(2);

            assertThat(unscheduled.requestedDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 20));
            assertThat(unscheduled.schedules()).isEmpty();
        }
    }

    @Nested
    @DisplayName("decode: nothing is flattened to one date per order (#1318)")
    class NothingFlattened {

        @Test
        void keepsEveryScheduleOfALine() throws IOException {
            assertThat(decodeSample().lines().getFirst().schedules()).hasSize(2);
        }

        @Test
        void keepsEveryDespatchOfASchedule() throws IOException {
            SupplierDeliverySchedule first =
                    decodeSample().lines().getFirst().schedules().getFirst();

            assertThat(first.despatches()).hasSize(2);
            assertThat(first.despatches())
                    .extracting(SupplierDespatch::despatchedQuantity)
                    .containsExactly(5, 3);
        }

        @Test
        void keepsALineConfirmedButNotYetDespatched() throws IOException {
            SupplierDeliverySchedule second =
                    decodeSample().lines().getFirst().schedules().get(1);

            assertThat(second.deliveryDate()).isEqualTo(LocalDate.of(2026, 8, 26));
            assertThat(second.confirmedQuantity()).isEqualTo(4);
            assertThat(second.despatches()).isEmpty();
        }

        @Test
        void keepsQuantityStillOpenAfterAPartialDespatch() throws IOException {
            SupplierDeliverySchedule partial =
                    decodeSample().lines().get(1).schedules().getFirst();

            assertThat(partial.confirmedQuantity()).isEqualTo(10);
            assertThat(partial.shippedQuantity()).isEqualTo(6);
            assertThat(partial.openQuantity()).isEqualTo(4);
            assertThat(partial.despatches())
                    .singleElement()
                    .satisfies(despatch ->
                            assertThat(despatch.despatchedQuantity()).isEqualTo(6));
        }

        @Test
        void keepsLineLevelGranularity() throws IOException {
            assertThat(decodeSample().lines())
                    .extracting(SupplierOrderStatusResult.Line::lineId)
                    .containsExactly("000001", "000002", "000003");
        }
    }

    @Nested
    @DisplayName("decode: the despatch advice reference is the ASN handle")
    class DespatchAdviceReference {

        @Test
        void isRetainedVerbatim() throws IOException {
            assertThat(decodeSample().lines().getFirst().schedules().getFirst().despatches())
                    .extracting(SupplierDespatch::despatchAdviceDocumentId)
                    .containsExactly("ASN-2026-08-11-0001", "ASN-2026-08-12-0007");
        }

        @Test
        void carriesTheDespatchAdviceLineWhenTheVendorStatesOne() throws IOException {
            var despatches =
                    decodeSample().lines().getFirst().schedules().getFirst().despatches();

            assertThat(despatches.getFirst().despatchAdviceLineId()).isEqualTo("000001");
            // The second despatch states no line, and that stays null rather than borrowing the
            // first one's.
            assertThat(despatches.get(1).despatchAdviceLineId()).isNull();
        }
    }

    @Nested
    @DisplayName("decode: absence is not zero")
    class AbsenceIsNotZero {

        @Test
        void leavesUnstatedQuantitiesNull() throws IOException {
            SupplierDeliverySchedule unshipped =
                    decodeSample().lines().getFirst().schedules().get(1);

            assertThat(unshipped.shippedQuantity()).isNull();
            assertThat(unshipped.cancelledQuantity()).isNull();
            assertThat(unshipped.backorderedQuantity()).isNull();
        }

        @Test
        void treatsAnExplicitZeroAsAStatement() throws IOException {
            // OpenQuantity 0 on the first schedule is the vendor saying nothing remains - a
            // different fact from the null on a schedule where it said nothing at all.
            assertThat(decodeSample().lines().getFirst().schedules().getFirst().openQuantity())
                    .isZero();
        }
    }

    @Nested
    @DisplayName("decode: what the vendor's answer means")
    class AnswerSemantics {

        @Test
        void readsAnOrderWithSchedulesAsConfirmed() throws IOException {
            SupplierOrderStatusResult result = decodeSample();

            assertThat(result.status()).isEqualTo(SupplierOrderStatusResult.Status.CONFIRMED);
            assertThat(result.supplierOrderNumber()).isEqualTo("MICH-770412");
            assertThat(result.orderDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        }

        @Test
        void readsAnOrderWithNoSchedulesAtAllAsInProgress() throws IOException {
            // Held but unscheduled. Distinct from CONFIRMED because only one of the two justifies
            // telling receiving that something is coming.
            SupplierOrderStatusResult result = codec.decode(DOCUMENT_ID, fixture("order-status-c11-unscheduled.xml"))
                    .result();

            assertThat(result.status()).isEqualTo(SupplierOrderStatusResult.Status.IN_PROGRESS);
            assertThat(result.lines()).hasSize(1);
            assertThat(result.lines().getFirst().schedules()).isEmpty();
            assertThat(result.lines().getFirst().orderedQuantity()).isEqualTo(4);
        }

        @Test
        void readsAnUnqualifiedDocument() throws IOException {
            // The unscheduled fixture carries no namespace at all. Rejecting it would turn a
            // readable answer about a real order into an unreconcilable ambiguity.
            assertThat(codec.decode(DOCUMENT_ID, fixture("order-status-c11-unscheduled.xml"))
                            .result()
                            .isKnownToVendor())
                    .isTrue();
        }

        @Test
        void reportsNotFoundWhenTheVendorListsNoOrders() throws IOException {
            SupplierOrderStatusResult result = codec.decode(DOCUMENT_ID, fixture("order-status-c11-unknown.xml"))
                    .result();

            assertThat(result.status()).isEqualTo(SupplierOrderStatusResult.Status.NOT_FOUND);
            assertThat(result.lines()).isEmpty();
        }

        @Test
        void neverAttributesAnotherOrdersDatesToOurs() throws IOException {
            EdiwheelC11OrderStatusCodec.Decoded decoded =
                    codec.decode(DOCUMENT_ID, fixture("order-status-c11-other-order.xml"));

            assertThat(decoded.result().status()).isEqualTo(SupplierOrderStatusResult.Status.NOT_FOUND);
            assertThat(decoded.result().lines()).isEmpty();
            assertThat(decoded.unmappedFields()).anyMatch(field -> field.contains("OrderReference"));
        }

        @Test
        void refusesToReadADocumentLevelVendorErrorAsAnAnswer() {
            String refused = """
                    <ew:order_status xmlns:ew="http://www.reifen.net">
                        <ew:DocumentID>C1</ew:DocumentID>
                        <ew:ErrorHead><ew:ErrorCode>913</ew:ErrorCode></ew:ErrorHead>
                    </ew:order_status>
                    """;

            // A refused QUERY must never decode to "the vendor has no such order": one is our
            // request being wrong, the other is grounds for a manual review of a real order.
            assertThatThrownBy(() -> codec.decode(DOCUMENT_ID, refused))
                    .isInstanceOf(OrderDecodeException.class)
                    .hasMessageContaining("913");
        }

        @Test
        void readsARejectedOrderAsRejectedWithTheVendorsReason() {
            String rejected = """
                    <ew:order_status xmlns:ew="http://www.reifen.net">
                        <ew:DocumentID>C1</ew:DocumentID>
                        <ew:ErrorHead><ew:ErrorCode>0</ew:ErrorCode></ew:ErrorHead>
                        <ew:ReferencedOrder>
                            <ew:Error>
                                <ew:ErrorCode>402</ew:ErrorCode>
                                <ew:ErrorText>Article no longer available</ew:ErrorText>
                            </ew:Error>
                            <ew:OrderReference><ew:DocumentID>%s</ew:DocumentID></ew:OrderReference>
                            <ew:OrderLine><ew:LineID>000001</ew:LineID></ew:OrderLine>
                        </ew:ReferencedOrder>
                    </ew:order_status>
                    """.formatted(DOCUMENT_ID);

            SupplierOrderStatusResult result =
                    codec.decode(DOCUMENT_ID, rejected).result();

            // An order-level error is the vendor answering "no", which must not read as the
            // header-level "your query is malformed" nor as an order still in progress.
            assertThat(result.status()).isEqualTo(SupplierOrderStatusResult.Status.REJECTED);
            assertThat(result.vendorErrorCode()).isEqualTo("402");
            assertThat(result.vendorReason()).isEqualTo("Article no longer available");
        }

        @Test
        void assumesASingleTerseAnswerIsOursAndRecordsTheAssumption() {
            String terse = """
                    <ew:order_status xmlns:ew="http://www.reifen.net">
                        <ew:DocumentID>C1</ew:DocumentID>
                        <ew:ReferencedOrder>
                            <ew:OrderLine><ew:LineID>000001</ew:LineID></ew:OrderLine>
                        </ew:ReferencedOrder>
                    </ew:order_status>
                    """;

            EdiwheelC11OrderStatusCodec.Decoded decoded = codec.decode(DOCUMENT_ID, terse);

            // One answer to a single-reference query, echoing no reference: taken as ours, but
            // the assumption is recorded — silently trusting it would be indistinguishable from
            // a genuine match in the reconciliation log.
            assertThat(decoded.result().isKnownToVendor()).isTrue();
            assertThat(decoded.unmappedFields()).anyMatch(field -> field.contains("single answer assumed to be ours"));
        }

        @Test
        void readsALineWithNoArticleBlockAsAllUnknowns() {
            String bare = """
                    <ew:order_status xmlns:ew="http://www.reifen.net">
                        <ew:DocumentID>C1</ew:DocumentID>
                        <ew:ReferencedOrder>
                            <ew:OrderReference><ew:DocumentID>%s</ew:DocumentID></ew:OrderReference>
                            <ew:OrderLine>
                                <ew:LineID>000009</ew:LineID>
                                <ew:CustomerLineItemNumber>000009</ew:CustomerLineItemNumber>
                            </ew:OrderLine>
                        </ew:ReferencedOrder>
                    </ew:order_status>
                    """.formatted(DOCUMENT_ID);

            SupplierOrderStatusResult.Line line =
                    codec.decode(DOCUMENT_ID, bare).result().lines().getFirst();

            // A vendor acknowledging a line without repeating the article must yield a line of
            // unknowns, not a crash: the line id is still the reconciliation key.
            assertThat(line.lineId()).isEqualTo("000009");
            assertThat(line.articleEan()).isNull();
            assertThat(line.orderedQuantity()).isNull();
            assertThat(line.requestedDeliveryDate()).isNull();
            assertThat(line.schedules()).isEmpty();
        }

        @Test
        void recordsEveryUnreadableDateAndQuantityInsteadOfReadingThemAsAbsent() {
            String garbled = """
                    <ew:order_status xmlns:ew="http://www.reifen.net">
                        <ew:DocumentID>C1</ew:DocumentID>
                        <ew:ReferencedOrder>
                            <ew:OrderDate>NEXT-TUESDAY</ew:OrderDate>
                            <ew:OrderReference><ew:DocumentID>%s</ew:DocumentID></ew:OrderReference>
                            <ew:OrderLine>
                                <ew:LineID>000001</ew:LineID>
                                <ew:OrderedArticle>
                                    <ew:RequestedDeliveryDate>SOON</ew:RequestedDeliveryDate>
                                    <ew:ScheduleDetails>
                                        <ew:DeliveryDate>SOMETIME</ew:DeliveryDate>
                                        <ew:ShippedDetails>
                                            <ew:ShippedQuantity>
                                                <ew:QuantityValue>MANY</ew:QuantityValue>
                                            </ew:ShippedQuantity>
                                            <ew:DespatchedDetails>
                                                <ew:ScheduledArticleDespatchDetails>
                                                    <ew:DespatchDate>YESTERDAYISH</ew:DespatchDate>
                                                </ew:ScheduledArticleDespatchDetails>
                                            </ew:DespatchedDetails>
                                        </ew:ShippedDetails>
                                    </ew:ScheduleDetails>
                                    <ew:OrderedQuantity>
                                        <ew:QuantityValue>A FEW</ew:QuantityValue>
                                    </ew:OrderedQuantity>
                                </ew:OrderedArticle>
                            </ew:OrderLine>
                        </ew:ReferencedOrder>
                    </ew:order_status>
                    """.formatted(DOCUMENT_ID);

            EdiwheelC11OrderStatusCodec.Decoded decoded = codec.decode(DOCUMENT_ID, garbled);

            // An unparseable value and an absent one both render as null; only these records
            // distinguish a vendor format change from a vendor silence.
            assertThat(decoded.unmappedFields())
                    .contains(
                            "ReferencedOrder/OrderDate",
                            "OrderedArticle/RequestedDeliveryDate",
                            "OrderedArticle/OrderedQuantity",
                            "ScheduleDetails/DeliveryDate",
                            "ScheduleDetails/ShippedDetails/ShippedQuantity",
                            "ScheduledArticleDespatchDetails/DespatchDate");
            SupplierDeliverySchedule schedule =
                    decoded.result().lines().getFirst().schedules().getFirst();
            assertThat(schedule.deliveryDate()).isNull();
            assertThat(schedule.despatches().getFirst().despatchAdviceDocumentId())
                    .isNull();
        }

        @Test
        void rejectsANullBody() {
            assertThatThrownBy(() -> codec.decode(DOCUMENT_ID, null)).isInstanceOf(OrderDecodeException.class);
        }

        @Test
        void rejectsAnEmptyBody() {
            assertThatThrownBy(() -> codec.decode(DOCUMENT_ID, "  ")).isInstanceOf(OrderDecodeException.class);
        }

        @Test
        void rejectsABodyThatIsNotAStatusDocument() {
            assertThatThrownBy(() -> codec.decode(DOCUMENT_ID, "{\"not\":\"xml\"}"))
                    .isInstanceOf(OrderDecodeException.class);
        }
    }
}
