package com.positivity.workorder.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.workorder.internal.client.InvoiceClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Verifies that InvoiceClient calls the correct direct Eureka service URL
 * (http://invoice/v1/invoices...) with the required X-User/X-Authorities headers.
 */
class InvoiceClientTest {

    private static final UUID INVOICE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void createInvoice_usesEurekaUrl_withAuthHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        InvoiceClient client =
                new InvoiceClient(builder.baseUrl("http://invoice").build());

        mockServer
                .expect(requestTo("http://invoice/v1/invoices"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-workorder"))
                .andExpect(header("X-Authorities", "invoice:manage"))
                .andRespond(withSuccess(
                        "{\"invoiceId\":\"" + INVOICE_ID + "\",\"status\":\"DRAFT\"}", MediaType.APPLICATION_JSON));

        InvoiceCreationRequest request =
                InvoiceCreationRequest.builder().workorderId(WORKORDER_ID).build();
        InvoiceGenerationResponse response = client.createInvoice(request);

        assertThat(response.getInvoiceId()).isEqualTo(INVOICE_ID);
        mockServer.verify();
    }

    @Test
    void getInvoice_usesEurekaUrl_withAuthHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        InvoiceClient client =
                new InvoiceClient(builder.baseUrl("http://invoice").build());

        mockServer
                .expect(requestTo("http://invoice/v1/invoices/" + INVOICE_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-workorder"))
                .andExpect(header("X-Authorities", "invoice:manage"))
                .andRespond(withSuccess(
                        "{\"invoiceId\":\"" + INVOICE_ID + "\",\"status\":\"OPEN\"}", MediaType.APPLICATION_JSON));

        InvoiceGenerationResponse response = client.getInvoice(INVOICE_ID);

        assertThat(response.getInvoiceId()).isEqualTo(INVOICE_ID);
        mockServer.verify();
    }
}
