package com.positivity.order.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/**
 * The supplier call as it behaves on the wire (CAP-319 #1329).
 *
 * <p>Every test here is about a failure that must not become an exception. A buyer is on the other
 * end deciding what to order, and a vendor having a bad afternoon must cost them an availability
 * column, not the screen.
 */
@DisplayName("SupplierStockClientImpl — asking a vendor what it can supply (#1329)")
class SupplierStockClientImplTest {

    private static final UUID VENDOR = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5b01");
    private static final UUID SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5b02");
    private static final List<SupplierStockClient.InquiryLine> ONE_LINE =
            List.of(new SupplierStockClient.InquiryLine("5012345678900", null, 5));

    private MockRestServiceServer server;
    private SupplierStockClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SupplierStockClientImpl(builder, "supplier", "/v1/supplier");
    }

    @AfterEach
    void tearDown() {
        server.reset();
    }

    private void respondWith(String json) {
        server.expect(MockRestRequestMatchers.requestTo("http://supplier/v1/supplier/stock/inquiries"))
                .andRespond(MockRestResponseCreators.withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("a vendor answer is carried through, null quantity and all")
    void answerIsCarriedThrough() {
        respondWith("""
                {"inquiryId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5b03","status":"OK",
                 "asOf":"2026-08-16T10:00:00Z",
                 "lines":[
                   {"articleEan":"5012345678900","supplierArticleCode":null,"status":"AVAILABLE",
                    "availableQuantity":12,"earliestDeliveryDate":"2026-08-20"},
                   {"articleEan":"5012345678917","supplierArticleCode":null,"status":"NOT_ANSWERED",
                    "availableQuantity":null,"earliestDeliveryDate":null}]}
                """);

        SupplierStockClient.StockAnswer answer = client.inquire(VENDOR, SITE, ONE_LINE);

        assertThat(answer.status()).isEqualTo(SupplierStockClient.Status.OK);
        assertThat(answer.answered()).isTrue();
        assertThat(answer.lines().get(0).availableQuantity()).isEqualTo(12);
        assertThat(answer.lines().get(0).earliestDeliveryDate()).hasToString("2026-08-20");
        // The null survives the hop. Defaulting it to zero here would be the single change that
        // turns "the vendor said nothing" into "the vendor has none" for everybody downstream.
        assertThat(answer.lines().get(1).availableQuantity()).isNull();
        assertThat(answer.lines().get(1).status()).isEqualTo(SupplierStockClient.LineStatus.NOT_ANSWERED);
    }

    @Test
    @DisplayName("a server error is an unavailable answer, not an exception")
    void serverErrorDegrades() {
        server.expect(MockRestRequestMatchers.requestTo("http://supplier/v1/supplier/stock/inquiries"))
                .andRespond(MockRestResponseCreators.withServerError());

        SupplierStockClient.StockAnswer answer = client.inquire(VENDOR, SITE, ONE_LINE);

        // Nothing rethrows. The caller renders "we could not ask" rather than failing the request
        // a buyer made.
        assertThat(answer.status()).isEqualTo(SupplierStockClient.Status.SUPPLIER_UNAVAILABLE);
        assertThat(answer.lines()).isEmpty();
        assertThat(answer.answered()).isFalse();
        assertThat(answer.asOf()).isNull();
    }

    @Test
    @DisplayName("an unparseable body is an unavailable answer too")
    void malformedBodyDegrades() {
        respondWith("{\"this\":\"is not an inquiry response\",\"status\":");

        assertThat(client.inquire(VENDOR, SITE, ONE_LINE).status())
                .isEqualTo(SupplierStockClient.Status.SUPPLIER_UNAVAILABLE);
    }

    @Test
    @DisplayName("a status nobody recognises is treated as no answer, never as a usable one")
    void unknownStatusIsNotTrusted() {
        respondWith("""
                {"inquiryId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5b03","status":"SOMETHING_NEW",
                 "asOf":null,"lines":[]}
                """);

        // A status this build has never heard of might mean anything. Guessing it is close enough
        // to OK would show a buyer quantities on a footing nobody has established.
        assertThat(client.inquire(VENDOR, SITE, ONE_LINE).status())
                .isEqualTo(SupplierStockClient.Status.SUPPLIER_UNAVAILABLE);
    }

    @Test
    @DisplayName("a line status nobody recognises becomes 'said nothing'")
    void unknownLineStatusIsNotAnswered() {
        respondWith("""
                {"inquiryId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5b03","status":"OK","asOf":null,
                 "lines":[{"articleEan":"5012345678900","supplierArticleCode":null,
                   "status":"PARTIALLY_SOMETHING","availableQuantity":3,"earliestDeliveryDate":null}]}
                """);

        assertThat(client.inquire(VENDOR, SITE, ONE_LINE).lines())
                .singleElement()
                .satisfies(line -> assertThat(line.status()).isEqualTo(SupplierStockClient.LineStatus.NOT_ANSWERED));
    }

    @Test
    @DisplayName("asking about nothing costs no round trip")
    void emptyInquiryMakesNoCall() {
        SupplierStockClient.StockAnswer answer = client.inquire(VENDOR, SITE, List.of());

        assertThat(answer.status()).isEqualTo(SupplierStockClient.Status.OK);
        assertThat(answer.lines()).isEmpty();
        // No expectation was set on the server, and none was needed: spending a vendor round trip
        // to learn nothing is a cost with no answer attached.
        server.verify();
    }

    @Test
    @DisplayName("the call carries the identity pos-supplier authorises it by")
    void callCarriesServiceIdentity() {
        server.expect(MockRestRequestMatchers.requestTo("http://supplier/v1/supplier/stock/inquiries"))
                .andExpect(MockRestRequestMatchers.header("X-User", "pos-order-service"))
                .andExpect(MockRestRequestMatchers.header("X-Authorities", "supplier:stock:inquire"))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {"inquiryId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5b03","status":"OK",
                         "asOf":null,"lines":[]}
                        """, MediaType.APPLICATION_JSON));

        // Without these the endpoint refuses the call and every answer degrades to
        // SUPPLIER_UNAVAILABLE — which looks exactly like a vendor being down, so the feature
        // would have appeared to work while never once reaching a supplier.
        assertThat(client.inquire(VENDOR, SITE, ONE_LINE).status()).isEqualTo(SupplierStockClient.Status.OK);
        server.verify();
    }
}
