package com.positivity.inventory.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.inventory.internal.enums.SourceDocumentType;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.internal.exception.SourceDocumentServiceUnavailableException;
import com.positivity.inventory.internal.exception.UnsupportedSourceDocumentTypeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Issue #1480 — receiving reads its source document from pos-order over a real endpoint, and each
 * failure gets an answer that means what it says.
 */
@DisplayName("SourceDocumentClient — purchase orders resolve from pos-order (#1480)")
class SourceDocumentClientTest {

    private static final String BASE = "http://order";
    private static final String PO_ID = "01a02fd3-b675-7000-8000-000000000001";

    private MockRestServiceServer server;
    private SourceDocumentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SourceDocumentClient(builder, "order");
    }

    @Test
    @DisplayName("an approved PO yields its lines with SKUs and expected quantities")
    void approvedPurchaseOrderYieldsItsLines() {
        server.expect(requestTo(BASE + "/v1/orders/purchase-orders/" + PO_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-inventory"))
                .andExpect(header("X-Authorities", "order:purchase_order:view"))
                .andRespond(withSuccess("""
                        {"purchaseOrderId":"%s","status":"APPROVED","lines":[
                          {"lineId":"11111111-1111-7000-8000-000000000001",
                           "skuId":"22222222-2222-7000-8000-000000000001",
                           "quantityDecimal":12,"documentUom":"CASE"}]}
                        """.formatted(PO_ID), MediaType.APPLICATION_JSON));

        SourceDocumentClient.SourceDocument document = client.fetchDocument(SourceDocumentType.PO, PO_ID);

        assertThat(document.alreadyReceived()).isFalse();
        assertThat(document.lines()).hasSize(1);
        assertThat(document.lines().getFirst().getProductId()).isEqualTo("22222222-2222-7000-8000-000000000001");
        assertThat(document.lines().getFirst().getExpectedQuantity()).isEqualByComparingTo("12");
    }

    /** A partially received PO must expect only what is still open, never the full ordered quantity. */
    @Test
    @DisplayName("a partially received PO expects only its open quantity")
    void partiallyReceivedPurchaseOrderExpectsOnlyWhatIsOpen() {
        respondWith("""
                {"status":"APPROVED","lines":[
                  {"lineId":"11111111-1111-7000-8000-000000000001",
                   "skuId":"22222222-2222-7000-8000-000000000001",
                   "quantityDecimal":12,"openQuantityDecimal":4}]}
                """);

        SourceDocumentClient.SourceDocument document = client.fetchDocument(SourceDocumentType.PO, PO_ID);

        assertThat(document.lines().getFirst().getExpectedQuantity()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("a PO whose lines are all closed reads as already received, not as an empty session")
    void fullyReceivedLinesReadAsAlreadyReceived() {
        respondWith("""
                {"status":"APPROVED","lines":[
                  {"lineId":"11111111-1111-7000-8000-000000000001",
                   "skuId":"22222222-2222-7000-8000-000000000001",
                   "quantityDecimal":12,"openQuantityDecimal":0}]}
                """);

        SourceDocumentClient.SourceDocument document = client.fetchDocument(SourceDocumentType.PO, PO_ID);

        assertThat(document.alreadyReceived()).isTrue();
        assertThat(document.lines()).isEmpty();
    }

    @Test
    @DisplayName("a closed PO status reads as already received")
    void closedStatusReadsAsAlreadyReceived() {
        respondWith("""
                {"status":"RECEIVED","lines":[
                  {"lineId":"11111111-1111-7000-8000-000000000001",
                   "skuId":"22222222-2222-7000-8000-000000000001","quantityDecimal":12}]}
                """);

        assertThat(client.fetchDocument(SourceDocumentType.PO, PO_ID).alreadyReceived())
                .isTrue();
    }

    @Test
    @DisplayName("a PO pos-order does not know is not found")
    void unknownPurchaseOrderIsNotFound() {
        server.expect(requestTo(BASE + "/v1/orders/purchase-orders/" + PO_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fetchDocument(SourceDocumentType.PO, PO_ID))
                .isInstanceOf(SourceDocumentNotFoundException.class)
                .hasMessageContaining(PO_ID);
    }

    /** The distinction the stub could not make: unreachable is not the same as nonexistent. */
    @Test
    @DisplayName("an unreachable pos-order is unavailable, not not-found")
    void unreachableOrderServiceIsUnavailable() {
        server.expect(requestTo(BASE + "/v1/orders/purchase-orders/" + PO_ID)).andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchDocument(SourceDocumentType.PO, PO_ID))
                .isInstanceOf(SourceDocumentServiceUnavailableException.class);
    }

    @Test
    @DisplayName("ASN is refused explicitly rather than resolved against a service that does not exist")
    void asnIsRefusedExplicitly() {
        assertThatThrownBy(() -> client.fetchDocument(SourceDocumentType.ASN, "ASN-1"))
                .isInstanceOf(UnsupportedSourceDocumentTypeException.class)
                .hasMessageContaining("ASN");
        server.verify();
    }

    private void respondWith(String body) {
        server.expect(requestTo(BASE + "/v1/orders/purchase-orders/" + PO_ID))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
