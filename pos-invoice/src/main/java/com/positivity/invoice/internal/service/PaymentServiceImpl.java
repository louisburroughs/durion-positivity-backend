package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.InitiatePaymentRequest;
import com.positivity.invoice.internal.dto.InitiatePaymentResponse;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.PaymentIntent;
import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.PaymentDeclinedException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.invoice.internal.enums.PaymentFlow;
import com.positivity.invoice.internal.enums.PaymentIntentStatus;
import com.positivity.invoice.internal.payment.GatewayCaptureRequest;
import com.positivity.invoice.internal.payment.GatewayPaymentResult;
import com.positivity.invoice.internal.payment.GatewayVoidRequest;
import com.positivity.invoice.internal.payment.PaymentGatewayPort;
import com.positivity.invoice.internal.payment.PaymentGatewayRequest;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.PaymentIntentRepository;
import com.positivity.invoice.service.PaymentService;
import com.positivity.security.common.SecurityContextHelper;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional
public class PaymentServiceImpl implements PaymentService {

    private static final BigDecimal PAYMENT_LIMIT_THRESHOLD = new BigDecimal("500.00");

    private static final String PROCESS_PAYMENT = "PROCESS_PAYMENT";
    private static final String OVERRIDE_PAYMENT_LIMIT = "OVERRIDE_PAYMENT_LIMIT";
    private static final String SELECT_PAYMENT_FLOW = "SELECT_PAYMENT_FLOW";
    private static final String MANUAL_CAPTURE = "MANUAL_CAPTURE";

    private final PaymentGatewayPort gatewayPort;
    private final InvoiceRepository invoiceRepository;
    private final PaymentIntentRepository paymentIntentRepository;

    public PaymentServiceImpl(
            @NonNull PaymentGatewayPort gatewayPort,
            @NonNull InvoiceRepository invoiceRepository,
            @NonNull PaymentIntentRepository paymentIntentRepository) {
        this.gatewayPort = gatewayPort;
        this.invoiceRepository = invoiceRepository;
        this.paymentIntentRepository = paymentIntentRepository;
    }

    @Override
    @NonNull
    public InitiatePaymentResponse initiatePayment(
            @NonNull UUID invoiceId,
            @NonNull InitiatePaymentRequest request) {
        requireAuthority(PROCESS_PAYMENT);

        if (request.getAmount().compareTo(PAYMENT_LIMIT_THRESHOLD) > 0) {
            requireAuthority(OVERRIDE_PAYMENT_LIMIT);
        }

        if (request.getPaymentFlow() == PaymentFlow.AUTH_ONLY) {
            requireAuthority(SELECT_PAYMENT_FLOW);
        }

        return paymentIntentRepository.findByIdempotencyKey(request.getIdempotencyKey())
                .map(paymentIntent -> {
                    if (paymentIntent.getInvoice() == null || !invoiceId.equals(paymentIntent.getInvoice().getId())) {
                        throw new PaymentIntentNotFoundException(
                                "Idempotency key " + request.getIdempotencyKey()
                                        + " not found under invoice " + invoiceId);
                    }
                    return toResponse(paymentIntent);
                })
                .orElseGet(() -> createAndProcessPaymentIntent(invoiceId, request));
    }

    @Override
    @NonNull
    public InitiatePaymentResponse capturePayment(
            @NonNull UUID invoiceId,
            @NonNull UUID paymentIntentId,
            @NonNull BigDecimal amount,
            @NonNull String captureIdempotencyKey) {
        requireAuthority(MANUAL_CAPTURE);

        PaymentIntent paymentIntent = paymentIntentRepository.findById(paymentIntentId)
                .orElseThrow(() -> new PaymentIntentNotFoundException("Payment intent not found: " + paymentIntentId));

        if (paymentIntent.getInvoice() == null || !paymentIntent.getInvoice().getId().equals(invoiceId)) {
            throw new PaymentIntentNotFoundException(
                    "Payment intent " + paymentIntentId + " not found under invoice " + invoiceId);
        }

        if (paymentIntent.getStatus() != PaymentIntentStatus.AUTHORIZED) {
            throw new InvalidPaymentStateException(
                    "Payment intent must be in AUTHORIZED status for capture, but was "
                            + paymentIntent.getStatus());
        }

        String originalGatewayReference = paymentIntent.getGatewayReference();
        paymentIntent.setStatus(PaymentIntentStatus.PENDING);
        PaymentIntent pendingPaymentIntent = paymentIntentRepository.save(paymentIntent);

        GatewayCaptureRequest captureRequest = new GatewayCaptureRequest(
                originalGatewayReference,
                amount,
                captureIdempotencyKey);

        GatewayPaymentResult captureResult = gatewayPort.capture(captureRequest);

        if (captureResult.isUnknown()) {
            captureResult = gatewayPort.inquireStatus(originalGatewayReference);
        }

        if (!captureResult.isSuccessful()) {
            pendingPaymentIntent.setStatus(PaymentIntentStatus.CAPTURE_FAILED);
            paymentIntentRepository.save(pendingPaymentIntent);
            throw new PaymentDeclinedException("Gateway declined the capture");
        }

        pendingPaymentIntent.setStatus(PaymentIntentStatus.CAPTURED);
        pendingPaymentIntent.setCapturedAmount(captureResult.getAmount());
        pendingPaymentIntent.setGatewayProvider(captureResult.getGatewayProvider());
        pendingPaymentIntent.setGatewayResponse(captureResult.getRawResponse());
        pendingPaymentIntent.setGatewayReference(captureResult.getGatewayReference());

        BigDecimal authorizedAmount = pendingPaymentIntent.getAuthorizedAmount();
        if (authorizedAmount != null && amount.compareTo(authorizedAmount) < 0) {
            BigDecimal remainder = authorizedAmount.subtract(amount);
            gatewayPort.voidRemainder(new GatewayVoidRequest(originalGatewayReference, remainder));
            pendingPaymentIntent.setVoidedRemainderAmount(remainder);
        }

        PaymentIntent saved = paymentIntentRepository.save(pendingPaymentIntent);
        return toResponse(saved);
    }

    @NonNull
    private InitiatePaymentResponse createAndProcessPaymentIntent(
            @NonNull UUID invoiceId,
            @NonNull InitiatePaymentRequest request) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));

        PaymentIntent paymentIntent = new PaymentIntent();
        paymentIntent.setInvoice(invoice);
        paymentIntent.setIdempotencyKey(request.getIdempotencyKey());
        paymentIntent.setStatus(PaymentIntentStatus.PENDING);
        paymentIntent.setPaymentFlow(request.getPaymentFlow());
        paymentIntent.setPaymentToken(request.getPaymentToken());
        paymentIntent.setAuthorizedAmount(request.getAmount());
        paymentIntent.setCapturedAmount(BigDecimal.ZERO);
        paymentIntent.setVoidedRemainderAmount(BigDecimal.ZERO);

        PaymentIntent savedPaymentIntent = paymentIntentRepository.save(paymentIntent);

        PaymentGatewayRequest gatewayRequest = new PaymentGatewayRequest(
                request.getAmount(),
                request.getPaymentToken(),
                request.getIdempotencyKey(),
                invoiceId);

        GatewayPaymentResult result;
        if (request.getPaymentFlow() == PaymentFlow.SALE_CAPTURE) {
            result = gatewayPort.saleCapture(gatewayRequest);
            if (result.isUnknown()) {
                result = gatewayPort.inquireStatus(result.getGatewayReference());
            }
        } else {
            result = gatewayPort.authorize(gatewayRequest);
            if (result.isUnknown()) {
                result = gatewayPort.inquireStatus(result.getGatewayReference());
            }
        }

        if (!result.isSuccessful()) {
            savedPaymentIntent.setStatus(PaymentIntentStatus.CAPTURE_FAILED);
            paymentIntentRepository.save(savedPaymentIntent);
            throw new PaymentDeclinedException("Gateway declined the payment");
        }

        savedPaymentIntent.setGatewayProvider(result.getGatewayProvider());
        savedPaymentIntent.setGatewayResponse(result.getRawResponse());
        savedPaymentIntent.setGatewayReference(result.getGatewayReference());

        if (request.getPaymentFlow() == PaymentFlow.AUTH_ONLY) {
            savedPaymentIntent.setStatus(PaymentIntentStatus.AUTHORIZED);
            savedPaymentIntent.setAuthorizedAmount(result.getAmount());
            savedPaymentIntent.setCapturedAmount(BigDecimal.ZERO);
            savedPaymentIntent.setVoidedRemainderAmount(BigDecimal.ZERO);
        } else {
            savedPaymentIntent.setStatus(PaymentIntentStatus.CAPTURED);
            savedPaymentIntent.setAuthorizedAmount(result.getAmount());
            savedPaymentIntent.setCapturedAmount(result.getAmount());
            savedPaymentIntent.setVoidedRemainderAmount(BigDecimal.ZERO);
        }

        PaymentIntent saved = paymentIntentRepository.save(savedPaymentIntent);
        return toResponse(saved);
    }

    private void requireAuthority(@NonNull String authority) {
        if (!SecurityContextHelper.hasAuthority(authority)) {
            throw new AccessDeniedException("Missing authority: " + authority);
        }
    }

    @NonNull
    private InitiatePaymentResponse toResponse(@NonNull PaymentIntent paymentIntent) {
        InitiatePaymentResponse response = new InitiatePaymentResponse();
        response.setPaymentIntentId(paymentIntent.getId());
        response.setStatus(paymentIntent.getStatus());
        response.setAuthorizedAmount(paymentIntent.getAuthorizedAmount());
        response.setCapturedAmount(paymentIntent.getCapturedAmount());
        response.setVoidedRemainderAmount(paymentIntent.getVoidedRemainderAmount());
        response.setGatewayProvider(paymentIntent.getGatewayProvider());
        response.setGatewayResponse(paymentIntent.getGatewayResponse());
        return response;
    }
}
