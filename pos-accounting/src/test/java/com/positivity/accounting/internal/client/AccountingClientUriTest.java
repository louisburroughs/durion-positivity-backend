package com.positivity.accounting.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Verifies that each accounting service client calls the correct direct Eureka
 * service URL (http://{serviceId}/v1/...) with the required auth headers.
 */
class AccountingClientUriTest {

    private static final UUID PARTY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INVOICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID WORKORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    // -----------------------------------------------------------------------
    // WorkorderInvoiceClient
    // -----------------------------------------------------------------------

    @Test
    void workorderInvoiceClient_usesEurekaUrl_withAuthHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        WorkorderInvoiceClient client = new WorkorderInvoiceClient(restClient, "workorder");

        mockServer
                .expect(requestTo("http://workorder/v1/workorders/" + WORKORDER_ID + "/generate-invoice"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-accounting"))
                .andExpect(header("X-Authorities", "workorder:workorder:generate_invoice"))
                .andRespond(withSuccess(
                        "{\"invoiceId\":\"22222222-2222-2222-2222-222222222222\","
                                + "\"status\":\"DRAFT\","
                                + "\"workorderId\":\"" + WORKORDER_ID + "\"}",
                        MediaType.APPLICATION_JSON));

        InvoiceGenerationResponse response = client.regenerateInvoiceFromWorkorder(WORKORDER_ID, null);

        assertThat(response).isNotNull();
        mockServer.verify();
    }
}
