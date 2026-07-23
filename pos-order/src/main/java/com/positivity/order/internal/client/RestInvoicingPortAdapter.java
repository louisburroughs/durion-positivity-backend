package com.positivity.order.internal.client;

import com.positivity.order.internal.exception.InvoicingUnavailableException;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import com.positivity.shared.dto.OrderInvoiceResponse;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * REST adapter on pos-invoice for the checkout/cancellation money flows (parity stories C1/C3).
 * Synchronous by design and by ADR-0044 amendment (scoped pos-order → pos-invoice exception, same
 * class of counter-flow as pos-warranty settlement): invoice creation must fail the checkout
 * loudly, and saga reversals must know their outcome in the request path. Settlement
 * <em>signals</em> flow back asynchronously on {@code payment.events.v1}.
 */
@Component
@Slf4j
public class RestInvoicingPortAdapter implements InvoicingPort {

    private final RestClient invoiceServiceRestClient;

    public RestInvoicingPortAdapter(@Qualifier("invoiceServiceRestClient") RestClient invoiceServiceRestClient) {
        this.invoiceServiceRestClient = invoiceServiceRestClient;
    }

    @Override
    public @NonNull InvoiceRef createInvoiceForOrder(@NonNull OrderInvoiceCreationRequest request) {
        try {
            OrderInvoiceResponse response = invoiceServiceRestClient
                    .post()
                    .uri("/v1/invoices/from-order")
                    .header("X-User", "pos-order")
                    .header("X-Authorities", "invoice:manage")
                    .body(request)
                    .retrieve()
                    .body(OrderInvoiceResponse.class);
            if (response == null || response.getInvoiceId() == null) {
                throw new InvoicingUnavailableException("pos-invoice returned an empty from-order response");
            }
            return new InvoiceRef(
                    response.getInvoiceId(),
                    response.getInvoiceNumber(),
                    response.getStatus(),
                    response.getTotalAmount(),
                    response.isExisting());
        } catch (InvoicingUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new InvoicingUnavailableException(
                    "Invoice creation failed for order " + request.getOrderId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public @NonNull PaymentReversalResult reversePayment(
            @NonNull UUID invoiceId, @NonNull UUID paymentIntentId, @NonNull ReversePaymentCommand command) {
        boolean isVoid = "VOID".equalsIgnoreCase(command.type());
        String path = "/v1/invoices/{invoiceId}/payments/{paymentId}/" + (isVoid ? "void" : "refunds");
        Map<String, Object> body = isVoid
                ? Map.of("reason", "CUSTOMER_REQUEST", "notes", reasonNotes(command))
                : Map.of(
                        "amount", command.amount(),
                        "reason", "CUSTOMER_RETURN",
                        "notes", reasonNotes(command),
                        "externalReference", command.idempotencyKey());
        try {
            invoiceServiceRestClient
                    .post()
                    .uri(path, invoiceId, paymentIntentId)
                    .header("X-User", "pos-order")
                    .header("X-Authorities", "invoice:manage," + (isVoid ? "VOID_PAYMENT" : "REFUND_PAYMENT"))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return new PaymentReversalResult(true, isVoid ? "VOID" : "REFUND", "Reversed by pos-invoice");
        } catch (Exception e) {
            log.warn(
                    "Payment reversal failed invoiceId={} paymentIntentId={}: {}",
                    invoiceId,
                    paymentIntentId,
                    e.getMessage());
            return new PaymentReversalResult(false, command.type(), e.getMessage());
        }
    }

    private static String reasonNotes(ReversePaymentCommand command) {
        String reason = command.reasonCode() == null ? "order cancellation" : command.reasonCode();
        return "pos-order cancellation saga (order " + command.orderId() + "): " + reason;
    }
}
