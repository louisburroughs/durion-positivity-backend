package com.positivity.order.internal.client;

import com.positivity.order.internal.exception.CartIdempotencyConflictException;
import com.positivity.order.internal.exception.InvoicingUnavailableException;
import com.positivity.order.internal.exception.OrderConflictException;
import com.positivity.order.internal.exception.OrderCustomerRequiredException;
import com.positivity.security.common.SecurityContextHelper;
import org.springframework.security.access.AccessDeniedException;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import com.positivity.shared.dto.OrderInvoiceResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
    private static final String SERVICE_USER = "pos-order";
    /** pos-invoice's BILL-DEC-010 elevation, forwarded only when the caller holds it (#2662). */
    static final String INVOICE_PAYMENT_LIMIT_OVERRIDE = "invoice:payment:limit_override";

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
                    .header(GatewaySecurityConstants.HEADER_USER, SERVICE_USER)
                    .header(GatewaySecurityConstants.HEADER_AUTHORITIES, "invoice:manage")
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
                // The refund states its currency (ADR-0067 PC-3 R-1). pos-invoice's refund body does not
                // declare it until payment intents carry a currency to compare it with (PC-12).
                : Map.of(
                        "amount", command.amount(),
                        "currencyCode", command.currency(),
                        "reason", "CUSTOMER_RETURN",
                        "notes", reasonNotes(command),
                        "externalReference", command.idempotencyKey());
        try {
            invoiceServiceRestClient
                    .post()
                    .uri(path, invoiceId, paymentIntentId)
                    .header(GatewaySecurityConstants.HEADER_USER, SERVICE_USER)
                    .header(
                            GatewaySecurityConstants.HEADER_AUTHORITIES,
                            "invoice:manage," + (isVoid ? "invoice:payment:void" : "invoice:payment:refund"))
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

    @Override
    public @NonNull CashPaymentRef recordCashPayment(
            @NonNull UUID invoiceId, @NonNull RecordCashPaymentCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", command.idempotencyKey());
        body.put("amount", command.amount());
        body.put("settledAmount", command.settledAmount());
        body.put("cashRoundingAmount", command.cashRoundingAmount());
        if (command.cashIncrement() != null) {
            body.put("cashIncrement", command.cashIncrement());
        }
        body.put("currencyCode", command.currencyCode());
        body.put("sessionId", command.sessionId());
        if (command.terminalId() != null) {
            body.put("terminalId", command.terminalId());
        }
        if (command.cashierId() != null) {
            body.put("cashierId", command.cashierId());
        }
        try {
            Map<?, ?> response = invoiceServiceRestClient
                    .post()
                    .uri("/v1/invoices/{invoiceId}/payments/cash", invoiceId)
                    .header(GatewaySecurityConstants.HEADER_USER, SERVICE_USER)
                    .header(GatewaySecurityConstants.HEADER_AUTHORITIES, cashTenderAuthorities())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            Object intentId = response == null ? null : response.get("paymentIntentId");
            if (intentId == null) {
                throw new InvoicingUnavailableException("pos-invoice returned no paymentIntentId for the cash tender");
            }
            return new CashPaymentRef(UUID.fromString(intentId.toString()));
        } catch (RestClientResponseException e) {
            throw relayCashTenderRefusal(invoiceId, e);
        } catch (InvoicingUnavailableException
                | CartIdempotencyConflictException
                | OrderConflictException
                | OrderCustomerRequiredException
                | AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            throw new InvoicingUnavailableException(
                    "pos-invoice unreachable for the cash tender on invoice " + invoiceId + ": " + e.getMessage(), e);
        }
    }

    /**
     * pos-invoice is the authority on the key, the balance, the party and the payment limit: each refusal is relayed
     * as the register's own code (Order ruling §1, §5; ADR-0017), by pos-invoice's ApiError code rather than by
     * status alone. Anything that is not a refusal is the invoicing handshake failing.
     */
    private static RuntimeException relayCashTenderRefusal(UUID invoiceId, RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String code = errorCode(e);
        String message = errorMessage(e);
        if (status == 409) {
            // PAYMENT_IDEMPOTENCY_CONFLICT: another payload under the key, or the key being recorded concurrently;
            // the message says which (a concurrent one asks for a retry).
            return new CartIdempotencyConflictException("pos-invoice refused the Idempotency-Key: " + message);
        }
        if (status == 403) {
            return new AccessDeniedException("pos-invoice refused the cash tender: " + message + " (" + code + ")");
        }
        if (status == 422 && "INVOICE_PARTY_REQUIRED".equals(code)) {
            return new OrderCustomerRequiredException();
        }
        if (status >= 400 && status < 500) {
            // CASH_ROUNDING_INVALID, an invoice pos-invoice no longer knows, a body it rejects: the register's view
            // disagrees with pos-invoice's; nothing was recorded, re-read the order before trying again.
            return new OrderConflictException(
                    "pos-invoice refused the cash tender (" + code + ": " + message + "); re-read the order and retry");
        }
        return new InvoicingUnavailableException(
                "pos-invoice answered " + status + " to the cash tender on invoice " + invoiceId, e);
    }

    /**
     * The authorities the service call carries (ADR-0044 scoped REST edge): the cash tender's own, plus the
     * BILL-DEC-010 limit override only when the clerk at the register holds it, so pos-invoice's control above 500.00
     * fires for a clerk who does not.
     */
    private static String cashTenderAuthorities() {
        String base = "invoice:manage,invoice:payment:process";
        return SecurityContextHelper.hasAuthority(INVOICE_PAYMENT_LIMIT_OVERRIDE)
                ? base + "," + INVOICE_PAYMENT_LIMIT_OVERRIDE
                : base;
    }

    private static String errorMessage(RestClientResponseException e) {
        try {
            Map<?, ?> error = e.getResponseBodyAs(Map.class);
            Object message = error == null ? null : error.get("message");
            return message == null ? e.getStatusText() : message.toString();
        } catch (RuntimeException unreadable) {
            return e.getStatusText();
        }
    }

    /** The ApiError code of a refusal, for the operator's log line; never the body (ADR-0072). */
    private static String errorCode(RestClientResponseException e) {
        try {
            Map<?, ?> error = e.getResponseBodyAs(Map.class);
            Object code = error == null ? null : error.get("code");
            return code == null ? String.valueOf(e.getStatusCode().value()) : code.toString();
        } catch (RuntimeException unreadable) {
            return String.valueOf(e.getStatusCode().value());
        }
    }

    @Override
    public void cancelInvoice(@NonNull UUID invoiceId) {
        try {
            invoiceServiceRestClient
                    .post()
                    .uri("/v1/invoices/{invoiceId}/cancel", invoiceId)
                    .header(GatewaySecurityConstants.HEADER_USER, SERVICE_USER)
                    .header(GatewaySecurityConstants.HEADER_AUTHORITIES, "invoice:manage")
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new InvoicingUnavailableException(
                    "Invoice cancel failed for invoice " + invoiceId + ": " + e.getMessage(), e);
        }
    }

    private static String reasonNotes(ReversePaymentCommand command) {
        String reason = command.reasonCode() == null ? "order cancellation" : command.reasonCode();
        return "pos-order cancellation saga (order " + command.orderId() + "): " + reason;
    }
}
