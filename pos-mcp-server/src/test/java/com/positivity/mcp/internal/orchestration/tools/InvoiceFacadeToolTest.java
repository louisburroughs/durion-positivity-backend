package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                contract("getInvoicesByCustomer").template(),
                contract("getRevenueByCustomer").template(),
                InvoiceFacadeTool.CUSTOMER_INVOICE_LINE_CAP);
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
    @DisplayName("searchInvoices sends GET /invoices/search?q={query} when no structured filters are given")
    void searchInvoices_sendsGetToSearchEndpoint() {
        FacadeContractManifest.Entry entry = contract("searchInvoices");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "Acme"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchInvoices("Acme", null, null, null, null);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchInvoices appends status, issuedFrom, issuedTo and customerId as query params when supplied")
    void searchInvoices_appendsStructuredFilters() {
        FacadeContractManifest.Entry entry = contract("searchInvoices");
        mockServer
                .expect(requestTo(BASE_URL
                        + entry.expand(Map.of("query", "Acme"))
                        + "&status=POSTED"
                        + "&issuedFrom=2026-06-01"
                        + "&issuedTo=2026-06-30"
                        + "&customerId=" + PARTY_ID))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchInvoices("Acme", "POSTED", "2026-06-01", "2026-06-30", PARTY_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("searchInvoices omits blank structured filters from the request URI")
    void searchInvoices_omitsBlankFilters() {
        FacadeContractManifest.Entry entry = contract("searchInvoices");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("query", "Acme")) + "&customerId=" + PARTY_ID))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.searchInvoices("Acme", "  ", null, "  ", PARTY_ID);

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getInvoicesByCustomer de-duplicates line rows and reports truncated=false below the 200-line cap")
    void getInvoicesByCustomer_deduplicatesLineRowsByInvoice() {
        FacadeContractManifest.Entry entry = contract("getInvoicesByCustomer");
        String lineRows = """
                [
                  {"invoiceId":"%s","invoiceNumber":"INV-1","invoiceStatus":"POSTED",\
                "invoiceCreatedAt":"2026-08-20T10:00:00Z","invoiceItemId":"i1"},
                  {"invoiceId":"%s","invoiceNumber":"INV-1","invoiceStatus":"POSTED",\
                "invoiceCreatedAt":"2026-08-20T10:00:00Z","invoiceItemId":"i2"},
                  {"invoiceId":"%s","invoiceNumber":"INV-2","invoiceStatus":"DRAFT",\
                "invoiceCreatedAt":"2026-07-01T09:30:00Z","invoiceItemId":"i3"}
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
        assertThat(result)
                .contains("\"truncated\":false")
                .contains("\"coveredFrom\":\"2026-07-01T09:30:00Z\"")
                .contains("\"coveredTo\":\"2026-08-20T10:00:00Z\"")
                .contains("\"invoices\":[");
    }

    @Test
    @DisplayName("covered range orders mixed-offset timestamps on the instant timeline, not lexicographically")
    void getInvoicesByCustomer_coveredRangeHandlesMixedOffsets() {
        FacadeContractManifest.Entry entry = contract("getInvoicesByCustomer");
        // Lexicographically "+02:00" sorts before "Z", but 10:00+02:00 == 08:00Z is the EARLIER
        // instant and 09:00Z is the later one.
        String lineRows = """
                [
                  {"invoiceId":"%s","invoiceCreatedAt":"2026-08-20T10:00:00+02:00"},
                  {"invoiceId":"%s","invoiceCreatedAt":"2026-08-20T09:00:00Z"}
                ]
                """.formatted(INVOICE_A, INVOICE_B);
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("customerId", PARTY_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(lineRows, MediaType.APPLICATION_JSON));

        String result = tool.getInvoicesByCustomer(PARTY_ID);

        mockServer.verify();
        assertThat(result)
                .contains("\"coveredFrom\":\"2026-08-20T10:00:00+02:00\"")
                .contains("\"coveredTo\":\"2026-08-20T09:00:00Z\"");
    }

    @Test
    @DisplayName("getInvoicesByCustomer reports truncated=true when the backing call returns the 200-line cap")
    void getInvoicesByCustomer_flagsTruncationAtLineCap() {
        FacadeContractManifest.Entry entry = contract("getInvoicesByCustomer");
        StringBuilder lineRows = new StringBuilder("[");
        for (int i = 0; i < InvoiceFacadeTool.CUSTOMER_INVOICE_LINE_CAP; i++) {
            if (i > 0) {
                lineRows.append(',');
            }
            lineRows.append("{\"invoiceId\":\"")
                    .append(INVOICE_A)
                    .append("\",\"invoiceNumber\":\"INV-1\",\"invoiceStatus\":\"POSTED\",")
                    .append("\"invoiceCreatedAt\":\"2026-08-")
                    .append(String.format("%02d", (i % 28) + 1))
                    .append("T00:00:00Z\"}");
        }
        lineRows.append(']');
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("customerId", PARTY_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(lineRows.toString(), MediaType.APPLICATION_JSON));

        String result = tool.getInvoicesByCustomer(PARTY_ID);

        mockServer.verify();
        assertThat(result)
                .contains("\"truncated\":true")
                .contains("\"coveredFrom\":\"2026-08-01T00:00:00Z\"")
                .contains("\"coveredTo\":\"2026-08-28T00:00:00Z\"")
                .contains("\"lineCount\":" + InvoiceFacadeTool.CUSTOMER_INVOICE_LINE_CAP);
    }

    @Test
    @DisplayName("getInvoicesByCustomer reports null covered range when line rows carry no timestamps")
    void getInvoicesByCustomer_nullCoveredRangeWithoutTimestamps() {
        FacadeContractManifest.Entry entry = contract("getInvoicesByCustomer");
        String lineRows = "[{\"invoiceId\":\"" + INVOICE_A + "\",\"invoiceNumber\":\"INV-1\"}]";
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("customerId", PARTY_ID))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(lineRows, MediaType.APPLICATION_JSON));

        String result = tool.getInvoicesByCustomer(PARTY_ID);

        mockServer.verify();
        assertThat(result)
                .contains("\"truncated\":false")
                .contains("\"coveredFrom\":null")
                .contains("\"coveredTo\":null");
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

    @Test
    @DisplayName("getRevenueByCustomer maps a YYYY-MM period to GET revenue-by-customer?startDate&endDate")
    void getRevenueByCustomer_mapsCalendarMonthToDateRange() {
        FacadeContractManifest.Entry entry = contract("getRevenueByCustomer");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("startDate", "2026-06-01", "endDate", "2026-06-30"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess(
                        "{\"startDate\":\"2026-06-01\",\"endDate\":\"2026-06-30\",\"rows\":[]}",
                        MediaType.APPLICATION_JSON));

        String result = tool.getRevenueByCustomer("2026-06");

        mockServer.verify();
        assertThat(result).isNotEmpty().contains("2026-06-01").contains("2026-06-30");
    }

    @Test
    @DisplayName("getRevenueByCustomer maps a YYYY period to the full calendar year")
    void getRevenueByCustomer_mapsCalendarYearToDateRange() {
        FacadeContractManifest.Entry entry = contract("getRevenueByCustomer");
        mockServer
                .expect(requestTo(BASE_URL + entry.expand(Map.of("startDate", "2026-01-01", "endDate", "2026-12-31"))))
                .andExpect(method(entry.httpMethod()))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));

        String result = tool.getRevenueByCustomer("2026");

        mockServer.verify();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("getRevenueByCustomer rejects an unsupported period form without issuing a request")
    void getRevenueByCustomer_rejectsUnsupportedPeriod() {
        assertThatThrownBy(() -> tool.getRevenueByCustomer("2025-Q1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM")
                .hasMessageContaining("YYYY");

        mockServer.verify();
    }
}
