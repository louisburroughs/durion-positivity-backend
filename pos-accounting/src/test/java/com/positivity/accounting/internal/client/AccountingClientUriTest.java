package com.positivity.accounting.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.accounting.internal.dto.ApplyCreditMemoRequest;
import com.positivity.accounting.internal.dto.ApplyCreditMemoResponse;
import com.positivity.accounting.internal.dto.ApplyPaymentToInvoiceRequest;
import com.positivity.accounting.internal.dto.ApplyPaymentToInvoiceResponse;
import com.positivity.accounting.internal.dto.BillingRuleRefResponse;
import com.positivity.accounting.internal.dto.InvoiceDetails;
import com.positivity.accounting.internal.dto.ReversePaymentApplicationRequest;
import com.positivity.accounting.internal.dto.ReversePaymentApplicationResponse;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Instant;
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
    // CustomerBillingRulesClient
    // -----------------------------------------------------------------------

    @Test
    void customerBillingRulesClient_usesEurekaUrl_withAuthHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        CustomerBillingRulesClient client = new CustomerBillingRulesClient(restClient, "customer");

        mockServer
                .expect(requestTo("http://customer/v1/crm/snapshot/party/" + PARTY_ID + "/billing-rules"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-accounting"))
                .andExpect(header("X-Authorities", "crm:party:view"))
                .andRespond(withSuccess(
                        "{\"poRequired\":false,\"taxExempt\":false,\"currency\":\"USD\"}", MediaType.APPLICATION_JSON));

        BillingRuleRefResponse response = client.getBillingRules(PARTY_ID);

        assertThat(response).isNotNull();
        mockServer.verify();
    }

    // -----------------------------------------------------------------------
    // InvoiceServiceClient
    // -----------------------------------------------------------------------

    @Test
    void invoiceServiceClient_getInvoiceDetails_usesEurekaUrl_withAuthHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        CircuitBreaker circuitBreaker =
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults()).circuitBreaker("test-invoice");

        InvoiceServiceClient client = new InvoiceServiceClient(restClient, circuitBreaker, "invoice");

        mockServer
                .expect(requestTo("http://invoice/v1/invoices/" + INVOICE_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-accounting"))
                .andExpect(header("X-Authorities", "invoice:manage"))
                .andRespond(withSuccess(
                        "{\"invoiceId\":\"" + INVOICE_ID + "\","
                                + "\"customerId\":\"11111111-1111-1111-1111-111111111111\","
                                + "\"status\":\"OPEN\","
                                + "\"totalAmount\":100.00,"
                                + "\"balanceDue\":100.00,"
                                + "\"currency\":\"USD\"}",
                        MediaType.APPLICATION_JSON));

        InvoiceDetails details = client.getInvoiceDetails(INVOICE_ID);

        assertThat(details).isNotNull();
        assertThat(details.getInvoiceId()).isEqualTo(INVOICE_ID);
        mockServer.verify();
    }

    @Test
    void invoiceServiceClient_applyPaymentToInvoice_usesEurekaUrl_withAuthHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        CircuitBreaker circuitBreaker = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults())
                .circuitBreaker("test-invoice-apply-payment");

        InvoiceServiceClient client = new InvoiceServiceClient(restClient, circuitBreaker, "invoice");

        mockServer
                .expect(requestTo("http://invoice/v1/invoices/" + INVOICE_ID + "/apply-payment"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-accounting"))
                .andExpect(header("X-Authorities", "invoice:manage"))
                .andRespond(withSuccess(
                        "{\"invoiceId\":\"" + INVOICE_ID + "\","
                                + "\"status\":\"PAID_IN_FULL\","
                                + "\"balanceBefore\":100.00,"
                                + "\"balanceAfter\":0.00,"
                                + "\"totalPaid\":100.00,"
                                + "\"totalAmount\":100.00}",
                        MediaType.APPLICATION_JSON));

        ApplyPaymentToInvoiceRequest request = ApplyPaymentToInvoiceRequest.builder()
                .paymentApplicationId(UUID.fromString("44444444-4444-4444-4444-444444444444"))
                .amountApplied(new BigDecimal("100.00"))
                .appliedAt(Instant.parse("2026-06-09T00:00:00Z"))
                .currency("USD")
                .build();

        ApplyPaymentToInvoiceResponse response = client.applyPaymentToInvoice(INVOICE_ID, request);

        assertThat(response).isNotNull();
        assertThat(response.getInvoiceId()).isEqualTo(INVOICE_ID);
        mockServer.verify();
    }

    @Test
    void invoiceServiceClient_reversePaymentApplication_usesEurekaUrl_withAuthHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        CircuitBreaker circuitBreaker = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults())
                .circuitBreaker("test-invoice-reverse-payment");

        InvoiceServiceClient client = new InvoiceServiceClient(restClient, circuitBreaker, "invoice");

        mockServer
                .expect(requestTo("http://invoice/v1/invoices/" + INVOICE_ID + "/reverse-payment"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-accounting"))
                .andExpect(header("X-Authorities", "invoice:manage"))
                .andRespond(withSuccess(
                        "{\"invoiceId\":\"" + INVOICE_ID + "\","
                                + "\"status\":\"OPEN\","
                                + "\"balanceBefore\":0.00,"
                                + "\"balanceDue\":100.00,"
                                + "\"totalPaid\":0.00,"
                                + "\"reason\":\"Error correction\"}",
                        MediaType.APPLICATION_JSON));

        ReversePaymentApplicationRequest request = ReversePaymentApplicationRequest.builder()
                .paymentApplicationId(UUID.fromString("44444444-4444-4444-4444-444444444444"))
                .amountToRestore(new BigDecimal("100.00"))
                .reason("Error correction")
                .reversedBy("test-user")
                .build();

        ReversePaymentApplicationResponse response = client.reversePaymentApplication(INVOICE_ID, request);

        assertThat(response).isNotNull();
        assertThat(response.getInvoiceId()).isEqualTo(INVOICE_ID);
        mockServer.verify();
    }

    @Test
    void invoiceServiceClient_applyCreditMemo_usesEurekaUrl_withAuthHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        CircuitBreaker circuitBreaker = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults())
                .circuitBreaker("test-invoice-apply-credit-memo");

        InvoiceServiceClient client = new InvoiceServiceClient(restClient, circuitBreaker, "invoice");

        mockServer
                .expect(requestTo("http://invoice/v1/invoices/" + INVOICE_ID + "/apply-credit-memo"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-accounting"))
                .andExpect(header("X-Authorities", "invoice:manage"))
                .andRespond(withSuccess(
                        "{\"invoiceId\":\"" + INVOICE_ID + "\","
                                + "\"balanceBefore\":100.00,"
                                + "\"balanceAfter\":75.00,"
                                + "\"status\":\"OPEN\","
                                + "\"creditMemoApplied\":25.00}",
                        MediaType.APPLICATION_JSON));

        ApplyCreditMemoRequest request = ApplyCreditMemoRequest.builder()
                .creditMemoId(UUID.fromString("55555555-5555-5555-5555-555555555555"))
                .totalAmount(new BigDecimal("25.00"))
                .appliedBy("test-user")
                .currency("USD")
                .build();

        ApplyCreditMemoResponse response = client.applyCreditMemo(INVOICE_ID, request);

        assertThat(response).isNotNull();
        assertThat(response.getInvoiceId()).isEqualTo(INVOICE_ID);
        mockServer.verify();
    }

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
