package com.positivity.order.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.order.internal.exception.InvoicingUnavailableException;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link RestInvoicingPortAdapter} (parity stories C1/C3).
 *
 * <p>
 * This adapter is synchronous by ADR-0044 amendment, and the two directions have
 * deliberately opposite failure policies:
 *
 * <ul>
 * <li><b>Invoice creation and cancellation fail loudly.</b> Checkout must not
 * complete without an invoice, so anything short of a usable response — a
 * transport error, a 5xx, or a 200 carrying no invoice id — becomes
 * {@link InvoicingUnavailableException}.</li>
 * <li><b>Payment reversal reports failure instead of throwing.</b> It runs inside
 * the cancellation saga, which needs the outcome to decide its next compensating
 * step; an exception there would abort the saga midway and strand the order.</li>
 * </ul>
 *
 * <p>
 * The VOID/REFUND split is also pinned: a void targets a different endpoint and
 * authority than a refund and carries no amount, because there are no captured
 * funds to return.
 */
@DisplayName("RestInvoicingPortAdapter — pos-invoice money flows")
class RestInvoicingPortAdapterTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-0000-0000-000000000072");
    private static final UUID PAYMENT_INTENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000073");

    private MockRestServiceServer server;
    private RestInvoicingPortAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://invoice");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new RestInvoicingPortAdapter(builder.build());
    }

    private static OrderInvoiceCreationRequest creationRequest() {
        OrderInvoiceCreationRequest request = new OrderInvoiceCreationRequest();
        request.setOrderId(ORDER_ID);
        request.setCustomerId(UUID.randomUUID());
        request.setLocationId(UUID.randomUUID());
        request.setSubtotal(new BigDecimal("100.00"));
        request.setTaxAmount(new BigDecimal("8.25"));
        request.setTotalAmount(new BigDecimal("108.25"));
        return request;
    }

    private static ReversePaymentCommand command(String type, String amount) {
        return new ReversePaymentCommand(
                type, amount == null ? null : new BigDecimal(amount), "USD", "CUSTOMER_REQUEST", ORDER_ID, "idem-1");
    }

    @Test
    @DisplayName("returns the invoice reference and reports a replayed invoice as existing")
    void createsInvoice() {
        server.expect(requestTo("http://invoice/v1/invoices/from-order"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User", "pos-order"))
                .andExpect(header("X-Authorities", "invoice:manage"))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andRespond(withSuccess("""
                        {"invoiceId":"%s","invoiceNumber":"INV-9","status":"OPEN",
                         "totalAmount":108.25,"existing":true}""".formatted(INVOICE_ID), MediaType.APPLICATION_JSON));

        InvoiceRef ref = adapter.createInvoiceForOrder(creationRequest());

        assertThat(ref.invoiceId()).isEqualTo(INVOICE_ID);
        assertThat(ref.invoiceNumber()).isEqualTo("INV-9");
        assertThat(ref.status()).isEqualTo("OPEN");
        assertThat(ref.totalAmount()).isEqualByComparingTo("108.25");
        // "existing" is how the caller knows a retry replayed rather than double-invoiced.
        assertThat(ref.existing()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("fails the checkout when pos-invoice answers without an invoice id")
    void emptyResponseIsFatal() {
        server.expect(requestTo("http://invoice/v1/invoices/from-order"))
                .andRespond(
                        withSuccess("{\"invoiceNumber\":\"INV-9\",\"existing\":false}", MediaType.APPLICATION_JSON));

        // A 200 with no id is not a usable invoice; treating it as success would complete a
        // checkout with nothing to collect against.
        assertThatThrownBy(() -> adapter.createInvoiceForOrder(creationRequest()))
                .isInstanceOf(InvoicingUnavailableException.class)
                .hasMessageContaining("empty from-order response");
    }

    @Test
    @DisplayName("fails the checkout when pos-invoice errors, naming the order")
    void serverErrorIsFatal() {
        server.expect(requestTo("http://invoice/v1/invoices/from-order")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.createInvoiceForOrder(creationRequest()))
                .isInstanceOf(InvoicingUnavailableException.class)
                .hasMessageContaining(ORDER_ID.toString());
    }

    @Test
    @DisplayName("voids an uncaptured authorization without sending an amount")
    void voidsPayment() {
        server.expect(requestTo(
                        "http://invoice/v1/invoices/%s/payments/%s/void".formatted(INVOICE_ID, PAYMENT_INTENT_ID)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Authorities", "invoice:manage,VOID_PAYMENT"))
                .andExpect(jsonPath("$.reason").value("CUSTOMER_REQUEST"))
                // No funds were captured, so there is no amount to return.
                .andExpect(jsonPath("$.amount").doesNotExist())
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        PaymentReversalResult result = adapter.reversePayment(INVOICE_ID, PAYMENT_INTENT_ID, command("VOID", null));

        assertThat(result.success()).isTrue();
        assertThat(result.paymentReversalType()).isEqualTo("VOID");
        server.verify();
    }

    @Test
    @DisplayName("refunds settled funds with the amount and the saga's idempotency key")
    void refundsPayment() {
        server.expect(requestTo(
                        "http://invoice/v1/invoices/%s/payments/%s/refunds".formatted(INVOICE_ID, PAYMENT_INTENT_ID)))
                .andExpect(header("X-Authorities", "invoice:manage,REFUND_PAYMENT"))
                .andExpect(jsonPath("$.amount").value(40.00))
                .andExpect(jsonPath("$.reason").value("CUSTOMER_RETURN"))
                // The key is what stops a saga retry refunding twice.
                .andExpect(jsonPath("$.externalReference").value("idem-1"))
                .andExpect(jsonPath("$.notes").value(org.hamcrest.Matchers.containsString(ORDER_ID.toString())))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        PaymentReversalResult result =
                adapter.reversePayment(INVOICE_ID, PAYMENT_INTENT_ID, command("REFUND", "40.00"));

        assertThat(result.success()).isTrue();
        assertThat(result.paymentReversalType()).isEqualTo("REFUND");
        server.verify();
    }

    @Test
    @DisplayName("reports a failed reversal rather than throwing, so the saga can decide what to do")
    void reversalFailureIsReportedNotThrown() {
        server.expect(requestTo(
                        "http://invoice/v1/invoices/%s/payments/%s/refunds".formatted(INVOICE_ID, PAYMENT_INTENT_ID)))
                .andRespond(withServerError());

        PaymentReversalResult result =
                adapter.reversePayment(INVOICE_ID, PAYMENT_INTENT_ID, command("REFUND", "40.00"));

        assertThat(result.success()).isFalse();
        // The command's own type is echoed back, since no reversal actually took a form.
        assertThat(result.paymentReversalType()).isEqualTo("REFUND");
        assertThat(result.resultMessage()).isNotBlank();
    }

    @Test
    @DisplayName("falls back to a default reason when the command carries none")
    void reversalWithoutReasonCode() {
        server.expect(requestTo(
                        "http://invoice/v1/invoices/%s/payments/%s/void".formatted(INVOICE_ID, PAYMENT_INTENT_ID)))
                .andExpect(jsonPath("$.notes").value(org.hamcrest.Matchers.containsString("order cancellation")))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        PaymentReversalResult result = adapter.reversePayment(
                INVOICE_ID,
                PAYMENT_INTENT_ID,
                new ReversePaymentCommand("void", null, "USD", null, ORDER_ID, "idem-2"));

        // Type matching is case-insensitive: "void" must not fall through to the refund path.
        assertThat(result.paymentReversalType()).isEqualTo("VOID");
        server.verify();
    }

    @Test
    @DisplayName("raises InvoicingUnavailable when a cancel cannot be delivered")
    void cancelFailureIsFatal() {
        server.expect(requestTo("http://invoice/v1/invoices/%s/cancel".formatted(INVOICE_ID)))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.cancelInvoice(INVOICE_ID))
                .isInstanceOf(InvoicingUnavailableException.class)
                .hasMessageContaining(INVOICE_ID.toString());
    }

    @Test
    @DisplayName("cancels an invoice against the invoice-manage authority")
    void cancelsInvoice() {
        server.expect(requestTo("http://invoice/v1/invoices/%s/cancel".formatted(INVOICE_ID)))
                .andExpect(header("X-User", "pos-order"))
                .andExpect(header("X-Authorities", "invoice:manage"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        adapter.cancelInvoice(INVOICE_ID);

        server.verify();
    }
}
