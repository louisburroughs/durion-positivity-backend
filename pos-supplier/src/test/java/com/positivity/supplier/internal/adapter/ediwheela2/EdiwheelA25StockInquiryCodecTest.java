package com.positivity.supplier.internal.adapter.ediwheela2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiry;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult.LineStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EDIWheel A2.5 stock inquiry codec (#1225)")
class EdiwheelA25StockInquiryCodecTest {

    private static final UUID INQUIRY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");

    private final EdiwheelA25StockInquiryCodec codec = new EdiwheelA25StockInquiryCodec();

    private static PartyContext party() {
        return new PartyContext("30012456", "92", UUID.randomUUID());
    }

    private static SupplierStockInquiry inquiry(SupplierStockInquiry.Line... lines) {
        return new SupplierStockInquiry(INQUIRY_ID, List.of(lines));
    }

    private static SupplierStockInquiry.Line line(String ean, String supplierCode, int quantity) {
        return new SupplierStockInquiry.Line(ean, supplierCode, quantity);
    }

    /** A quote wrapping the given order lines, with a clean header. */
    private static String quote(String orderLines) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ew:quote_A2 xmlns:ew="http://www.reifen.net">
                  <ew:DocumentID>A2</ew:DocumentID>
                  <ew:Variant>5</ew:Variant>
                  <ew:ErrorHead><ew:ErrorCode>0</ew:ErrorCode></ew:ErrorHead>
                  <ew:BuyerParty><ew:PartyID>30012456</ew:PartyID><ew:AgencyCode>92</ew:AgencyCode></ew:BuyerParty>
                %s
                </ew:quote_A2>
                """.formatted(orderLines);
    }

    private static String orderLine(String availability, String schedule, String error) {
        return """
                  <ew:OrderLine>
                    <ew:LineID>000001</ew:LineID>
                    <ew:OrderedArticle>
                      <ew:ArticleIdentification>
                        <ew:EANUCCArticleID>4019238001234</ew:EANUCCArticleID>
                        <ew:ManufacturersArticleID>MICH-123</ew:ManufacturersArticleID>
                      </ew:ArticleIdentification>
                      %s
                      %s
                      %s
                    </ew:OrderedArticle>
                  </ew:OrderLine>
                """.formatted(
                        availability == null ? "" : "<ew:Availability>" + availability + "</ew:Availability>",
                        schedule == null ? "" : schedule,
                        error == null ? "" : "<ew:Error><ew:ErrorCode>" + error + "</ew:ErrorCode></ew:Error>");
    }

    private static String schedule(String date, int quantity) {
        return """
                <ew:ScheduleDetails>
                  <ew:DeliveryDate>%s</ew:DeliveryDate>
                  <ew:ConfirmedQuantity><ew:QuantityValue>%d</ew:QuantityValue></ew:ConfirmedQuantity>
                </ew:ScheduleDetails>
                """.formatted(date, quantity);
    }

    @Test
    void registersUnderTheStockInquiryTriple() {
        assertThat(codec.capability()).isEqualTo(SupplierCapability.STOCK_INQUIRY);
        assertThat(codec.family()).isEqualTo(ProtocolFamily.EDIWHEEL_A25);
        assertThat(codec.version()).isEqualTo(ProtocolVersion.A2_5);
    }

    @Nested
    @DisplayName("encode")
    class Encode {

        @Test
        void writesTheNormsFixedDocumentIdentityAndBothParties() {
            String xml = codec.encodeInquiry(inquiry(line("4019238001234", null, 4)), party(), "50098765", "92");

            assertThat(xml).contains("<ew:DocumentID>A2</ew:DocumentID>");
            assertThat(xml).contains("<ew:Variant>5</ew:Variant>");
            assertThat(xml).contains("<ew:PartyID>30012456</ew:PartyID>");
            assertThat(xml).contains("<ew:PartyID>50098765</ew:PartyID>");
            assertThat(xml).contains("<ew:EANUCCArticleID>4019238001234</ew:EANUCCArticleID>");
            assertThat(xml).contains("<ew:QuantityValue>4</ew:QuantityValue>");
        }

        @Test
        void echoesTheInquiryIdSoTheQuoteCanBeTiedBackToTheAsk() {
            String xml = codec.encodeInquiry(inquiry(line("4019238001234", null, 1)), party(), "50098765", "92");

            assertThat(xml).contains(INQUIRY_ID.toString());
        }

        @Test
        void numbersLinesSoTheQuoteCanBeMatchedToThem() {
            String xml = codec.encodeInquiry(
                    inquiry(line("4019238001234", null, 1), line(null, "MICH-9", 2)), party(), "50098765", "92");

            assertThat(xml).contains("<ew:LineID>000001</ew:LineID>");
            assertThat(xml).contains("<ew:LineID>000002</ew:LineID>");
        }

        @Test
        void refusesToAskWithoutADeliveryAccount() {
            // Availability is consignee-specific. Asking without one would get an answer for
            // whichever address the vendor defaults to — right-looking and about the wrong place.
            assertThatThrownBy(() -> codec.encodeInquiry(inquiry(line("4019238001234", null, 1)), party(), null, "92"))
                    .isInstanceOf(StockInquiryEncodeException.class)
                    .hasMessageContaining("delivery account");
        }

        @Test
        void refusesToAskWithoutAnAgencyCode() {
            assertThatThrownBy(() ->
                            codec.encodeInquiry(inquiry(line("4019238001234", null, 1)), party(), "50098765", null))
                    .isInstanceOf(StockInquiryEncodeException.class)
                    .hasMessageContaining("agency code");
        }

        @Test
        void marksTheRequestRetryableBecauseAnInquiryCommitsNothing() {
            SupplierRequestSpec spec =
                    codec.buildRequest(inquiry(line("4019238001234", null, 1)), party(), "50098765", "92");

            assertThat(spec.method()).isEqualTo("POST");
            assertThat(spec.contentType()).isEqualTo("application/xml");
            assertThat(spec.idempotent()).isTrue();
        }
    }

    @Nested
    @DisplayName("decode")
    class Decode {

        @Test
        void readsAnAvailableLineWithItsScheduledQuantityAndEarliestDate() {
            SupplierStockInquiryResult result =
                    codec.decodeQuote(quote(orderLine("1", schedule("2026-08-20", 4), "0")));

            assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.OK);
            SupplierStockInquiryResult.Line line = result.lines().getFirst();
            assertThat(line.status()).isEqualTo(LineStatus.AVAILABLE);
            assertThat(line.availableQuantity()).isEqualTo(4);
            assertThat(line.earliestDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 20));
            assertThat(line.articleEan()).isEqualTo("4019238001234");
        }

        @Test
        void sumsAPartialAnswerAcrossItsPromisedDatesAndKeepsTheEarliest() {
            // Code 2 says "not in the requested quantity — consider the announced actual quantity",
            // so the schedule is the answer rather than a reason to report nothing.
            SupplierStockInquiryResult result = codec.decodeQuote(
                    quote(orderLine("2", schedule("2026-09-01", 2) + schedule("2026-08-25", 3), "0")));

            SupplierStockInquiryResult.Line line = result.lines().getFirst();
            assertThat(line.status()).isEqualTo(LineStatus.AVAILABLE);
            assertThat(line.availableQuantity()).isEqualTo(5);
            assertThat(line.earliestDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 25));
        }

        @Test
        void treatsAnExplicitlyUnavailableArticleAsAStatedZero() {
            SupplierStockInquiryResult result = codec.decodeQuote(quote(orderLine("3", null, "0")));

            SupplierStockInquiryResult.Line line = result.lines().getFirst();
            assertThat(line.status()).isEqualTo(LineStatus.UNAVAILABLE);
            // The one place a zero is a fact: the vendor said it has none.
            assertThat(line.availableQuantity()).isZero();
        }

        @Test
        void treatsAnUndeterminedAvailabilityAsSilenceNotAsZero() {
            SupplierStockInquiryResult result = codec.decodeQuote(quote(orderLine("0", null, "0")));

            SupplierStockInquiryResult.Line line = result.lines().getFirst();
            assertThat(line.status()).isEqualTo(LineStatus.NOT_ANSWERED);
            // Coercing this to 0 would print "out of stock" on the strength of a vendor shrug.
            assertThat(line.availableQuantity()).isNull();
        }

        @Test
        void readsAnUnknownEanAsNotListedRatherThanUnavailable() {
            // 911: incorrect EAN number (not known on supplier side). Retrying cannot help, which
            // is exactly what separates this from a vendor being down.
            SupplierStockInquiryResult result = codec.decodeQuote(quote(orderLine(null, null, "911")));

            assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.NOT_LISTED);
            assertThat(result.lines().getFirst().status()).isEqualTo(LineStatus.NOT_LISTED);
            assertThat(result.lines().getFirst().availableQuantity()).isNull();
        }

        @Test
        void readsAnUnknownSupplierMaterialNumberAsNotListed() {
            SupplierStockInquiryResult result = codec.decodeQuote(quote(orderLine(null, null, "940")));

            assertThat(result.lines().getFirst().status()).isEqualTo(LineStatus.NOT_LISTED);
        }

        @Test
        void readsANoLongerSellableArticleAsNotListed() {
            // 945: material status set to not sellable. The vendor knows it and will not sell it —
            // for a buyer that is the same answer as never having carried it.
            SupplierStockInquiryResult result = codec.decodeQuote(quote(orderLine(null, null, "945")));

            assertThat(result.lines().getFirst().status()).isEqualTo(LineStatus.NOT_LISTED);
        }

        @Test
        void doesNotCallALineNotListedForAnErrorAboutSomethingElse() {
            // 912: requested delivery date invalid. That says nothing about whether the vendor
            // stocks the article, so claiming NOT_LISTED would invent an answer.
            SupplierStockInquiryResult result = codec.decodeQuote(quote(orderLine(null, null, "912")));

            assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.OK);
            assertThat(result.lines().getFirst().status()).isEqualTo(LineStatus.NOT_ANSWERED);
        }

        @Test
        void reportsNotListedForTheDocumentOnlyWhenNoInquiredArticleIsCarried() {
            String mixed = orderLine("1", schedule("2026-08-20", 4), "0") + orderLine(null, null, "911");

            SupplierStockInquiryResult result = codec.decodeQuote(quote(mixed));

            // "We stock one of these two" is not "not listed"; the document is answered and the
            // detail lives on the lines.
            assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.OK);
            assertThat(result.lines())
                    .extracting(SupplierStockInquiryResult.Line::status)
                    .containsExactly(LineStatus.AVAILABLE, LineStatus.NOT_LISTED);
        }

        @Test
        void readsAProfileFaultAsConfigurationErrorRatherThanBlamingTheVendor() {
            // 927: missing mapping for the customer-assigned consignee id. The vendor is healthy;
            // our profile is wrong, and an operator told "supplier unavailable" would wait forever.
            String body = quote("").replace("<ew:ErrorCode>0</ew:ErrorCode>", "<ew:ErrorCode>927</ew:ErrorCode>");

            SupplierStockInquiryResult result = codec.decodeQuote(body);

            assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.CONFIGURATION_ERROR);
            assertThat(result.lines()).isEmpty();
        }

        @Test
        void readsAnUnrecognisedHeaderErrorAsSupplierUnavailable() {
            String body = quote("").replace("<ew:ErrorCode>0</ew:ErrorCode>", "<ew:ErrorCode>910</ew:ErrorCode>");

            SupplierStockInquiryResult result = codec.decodeQuote(body);

            assertThat(result.status()).isEqualTo(SupplierStockInquiryResult.Status.SUPPLIER_UNAVAILABLE);
        }

        @Test
        void acceptsAQuoteWhoseElementsArriveUnqualified() {
            // Vendors differ on whether they qualify children. Rejecting a well-formed answer over
            // a namespace would report an available article as an unavailable vendor.
            String unqualified = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <quote_A2>
                      <DocumentID>A2</DocumentID>
                      <ErrorHead><ErrorCode>0</ErrorCode></ErrorHead>
                      <OrderLine>
                        <LineID>000001</LineID>
                        <OrderedArticle>
                          <ArticleIdentification><EANUCCArticleID>4019238001234</EANUCCArticleID></ArticleIdentification>
                          <Availability>1</Availability>
                          <ScheduleDetails>
                            <DeliveryDate>20260820</DeliveryDate>
                            <ConfirmedQuantity><QuantityValue>6</QuantityValue></ConfirmedQuantity>
                          </ScheduleDetails>
                        </OrderedArticle>
                      </OrderLine>
                    </quote_A2>
                    """;

            SupplierStockInquiryResult result = codec.decodeQuote(unqualified);

            assertThat(result.lines().getFirst().availableQuantity()).isEqualTo(6);
            // yyyyMMdd as well as ISO: sibling norms state dates that way and vendors reuse them.
            assertThat(result.lines().getFirst().earliestDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        }

        @Test
        void rejectsABodyThatIsNotAQuoteRatherThanReadingItAsAnEmptyAnswer() {
            // An empty instance would carry no error and no lines, which reads as "the vendor
            // answered and had nothing" — a definite claim from an unreadable body.
            assertThatThrownBy(() -> codec.decodeQuote("<html><body>502 Bad Gateway</body></html>"))
                    .isInstanceOf(StockInquiryDecodeException.class);
        }

        @Test
        void rejectsAnEmptyBody() {
            assertThatThrownBy(() -> codec.decodeQuote("  ")).isInstanceOf(StockInquiryDecodeException.class);
        }

        @Test
        void neverQuotesAPriceBecauseTheNormCarriesNone() {
            SupplierStockInquiryResult result =
                    codec.decodeQuote(quote(orderLine("1", schedule("2026-08-20", 4), "0")));

            // A2.5 has no price element at all — only the C1.0 inquiry does. A supplier price comes
            // from the PRICAT entries that own it, never invented here.
            assertThat(result.lines().getFirst().quotedUnitPrice()).isNull();
            assertThat(result.lines().getFirst().currency()).isNull();
        }
    }
}
