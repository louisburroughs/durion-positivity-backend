package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link InvoiceFacadeTool}. Expected verbs and URIs derive from
 * {@code facade-contract.yaml} (#1519 WS-0.3), never from literals duplicating the configuration.
 */
class InvoiceFacadeToolTest {

    private static final String BASE_URL = "http://api-gateway";
    private static final String PARTY_ID = "01960003-0000-7000-8000-000000000050";
    private static final String INVOICE_A = "01960003-0000-7000-8000-0000000000a1";
    private static final String INVOICE_B = "01960003-0000-7000-8000-0000000000b2";

    private MockRestServiceServer mockServer;
    private InvoiceFacadeTool tool;

    private static FacadeContractManifest.Entry contract(String toolMethod) {
        return FacadeContractManifest.entry("InvoiceFacadeTool." + toolMethod);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tool = new InvoiceFacadeTool(
                builder,
                BASE_URL,
                contract("getInvoice").template(),
                contract("searchInvoices").template(),
                contract("getInvoicesByCustomer").template());
    }

    @Test
    @DisplayName("getInvoice sends GET /invoices/{invoiceId} and returns body")
    void getInvoice_sendsGetToInvoiceEndpoint() {
        FacadeContractManifest.Entry entry = contract("getInvoice");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("invoiceId", INVOICE_A))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"invoiceId\":\"" + INVOICE_A + "\"}", MediaType.APPLICATION_JSON));

        String result = tool.getInvoice(INVOICE_A);

        mockServer.verify();
        assertThat(result).isNotEmpty().contains(INVOICE_A);
    }

    @Test
    @DisplayName("searchInvoices sends GET /invoices/search?q={query} and returns body")
    void searchInvoices_sendsGetToSearchEndpoint() {
        FacadeContractManifest.Entry entry = contract("searchInvoices");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "POSTED"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchInvoices("POSTED");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getInvoicesByCustomer fetches line rows and de-duplicates them by owning invoice")
    void getInvoicesByCustomer_deduplicatesLineRowsByInvoice() {
        FacadeContractManifest.Entry entry = contract("getInvoicesByCustomer");
        String lineRows = """
                [
                  {"invoiceId":"%s","invoiceNumber":"INV-1","invoiceStatus":"POSTED","invoiceItemId":"i1"},
                  {"invoiceId":"%s","invoiceNumber":"INV-1","invoiceStatus":"POSTED","invoiceItemId":"i2"},
                  {"invoiceId":"%s","invoiceNumber":"INV-2","invoiceStatus":"DRAFT","invoiceItemId":"i3"}
                ]
                """.formatted(INVOICE_A, INVOICE_A, INVOICE_B);
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("customerId", PARTY_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(lineRows, MediaType.APPLICATION_JSON));

        String result = tool.getInvoicesByCustomer(PARTY_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
        assertThat(result).containsOnlyOnce(INVOICE_A).containsOnlyOnce(INVOICE_B);
        assertThat(result).contains("\"lineCount\":2").contains("\"lineCount\":1");
        assertThat(result).doesNotContain("invoiceItemId");
    }

    @Test
    @DisplayName("getInvoicesByCustomer passes a non-array payload (error envelope) through unchanged")
    void getInvoicesByCustomer_passesNonArrayThrough() {
        FacadeContractManifest.Entry entry = contract("getInvoicesByCustomer");
        String errorEnvelope = "{\"code\":\"NOT_FOUND\",\"message\":\"no lines\"}";
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("customerId", PARTY_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(errorEnvelope, MediaType.APPLICATION_JSON));

        String result = tool.getInvoicesByCustomer(PARTY_ID);

        mockServer.verify();
        assertThat(result).isEqualTo(errorEnvelope);
    }
}
