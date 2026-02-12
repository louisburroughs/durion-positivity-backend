package com.positivity.accounting.internal.client;

import com.positivity.accounting.internal.dto.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * REST client for Invoice service integration.
 * Handles payment application and reversal operations on invoices.
 *
 * **Responsibilities:**
 * - Fetch invoice details for validation (GET /v1/invoices/{invoiceId})
 * - Apply payment to invoice (POST /v1/invoices/{invoiceId}/apply-payment)
 * - Reverse payment application (POST /v1/invoices/{invoiceId}/reverse-payment)
 *
 * **Resilience:**
 * - Circuit breaker pattern with exponential backoff
 * - Connect and read timeouts configured in RestClient (see RestClientConfig)
 * - Configurable invoice service URL
 * - Detailed error logging for debugging
 * - Graceful fallbacks (throws InvoiceServiceException for caller handling)
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 * @see <a href=
 *      "https://resilience4j.readme.io/docs/circuitbreaker">Resilience4j
 *      Circuit Breaker</a>
 * @see com.positivity.accounting.internal.config.RestClientConfig
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceServiceClient {

    @Qualifier("invoiceServiceRestClient")
    private final RestClient restClient;
    private final CircuitBreaker invoiceServiceCircuitBreaker;

    @Value("${pos.invoice.service.url:http://pos-invoice:8085}")
    private String invoiceServiceUrl;

    /**
     * Fetch invoice details from Invoice service.
     * Used to validate invoice exists and is in applicable state.
     *
     * @param invoiceId invoice identifier
     * @return invoice details
     * @throws InvoiceServiceException if invoice not found or service unavailable
     */
    public InvoiceDetails getInvoiceDetails(UUID invoiceId) {
        log.debug("Fetching invoice details for invoice {}", invoiceId);

        Supplier<InvoiceDetails> invokeRemote = () -> {
            try {
                InvoiceDetails details = restClient.get()
                        .uri(invoiceServiceUrl + "/v1/invoices/{id}", invoiceId)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                            log.warn("Invoice service error: {} {}", httpResponse.getStatusCode(),
                                    httpResponse.getStatusText());
                            throw new InvoiceServiceException(
                                    "Invoice service returned " + httpResponse.getStatusCode(),
                                    httpResponse.getStatusCode().value());
                        })
                        .body(InvoiceDetails.class);

                if (details == null) {
                    log.warn("Invoice service returned null details for invoice {}", invoiceId);
                    throw new InvoiceServiceException("No invoice details returned", 500);
                }

                log.debug("Retrieved invoice {}: status={}, balanceDue={}",
                        invoiceId, details.getStatus(), details.getBalanceDue());

                return details;
            } catch (RestClientException e) {
                log.error("Failed to fetch invoice details for {}: {}", invoiceId, e.getMessage(), e);
                throw new InvoiceServiceException("Failed to fetch invoice details: " + e.getMessage(), e);
            }
        };

        // Execute with circuit breaker protection
        try {
            return invoiceServiceCircuitBreaker.executeSupplier(invokeRemote);
        } catch (Exception e) {
            if (e instanceof InvoiceServiceException invoiceServiceException) {
                throw invoiceServiceException;
            }
            log.error("Circuit breaker open for getInvoiceDetails({}): {}", invoiceId, e.getMessage());
            throw new InvoiceServiceException(
                    "Invoice service unavailable (circuit breaker open). Please retry later.",
                    503,
                    e);
        }
    }

    /**
     * Apply payment to invoice.
     * Updates invoice balance and status after accounting records payment
     * application.
     *
     * @param invoiceId invoice identifier
     * @param request   payment application details
     * @return invoice state after payment applied
     * @throws InvoiceServiceException if operation fails
     */
    public ApplyPaymentToInvoiceResponse applyPaymentToInvoice(
            UUID invoiceId,
            ApplyPaymentToInvoiceRequest request) {

        log.info("Applying payment {} to invoice {}: amount={}, currency={}",
                request.getPaymentApplicationId(), invoiceId, request.getAmountApplied(),
                request.getCurrency());

        Supplier<ApplyPaymentToInvoiceResponse> invokeRemote = () -> {
            try {
                ApplyPaymentToInvoiceResponse response = restClient.post()
                        .uri(invoiceServiceUrl + "/v1/invoices/{id}/apply-payment", invoiceId)
                        .body(request)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                            log.warn("Invoice service error applying payment: {} {}",
                                    httpResponse.getStatusCode(), httpResponse.getStatusText());
                            throw new InvoiceServiceException(
                                    "Invoice service returned " + httpResponse.getStatusCode(),
                                    httpResponse.getStatusCode().value());
                        })
                        .body(ApplyPaymentToInvoiceResponse.class);

                if (response == null) {
                    log.warn("Invoice service returned null response for apply payment on invoice {}",
                            invoiceId);
                    throw new InvoiceServiceException("No response from invoice service", 500);
                }

                log.info("Successfully applied payment to invoice {}: new status={}, balanceAfter={}",
                        invoiceId, response.getStatus(), response.getBalanceAfter());

                return response;
            } catch (RestClientException e) {
                log.error("Failed to apply payment to invoice {}: {}", invoiceId, e.getMessage(), e);
                throw new InvoiceServiceException(
                        "Failed to apply payment to invoice: " + e.getMessage(), e);
            }
        };

        try {
            return invoiceServiceCircuitBreaker.executeSupplier(invokeRemote);
        } catch (Exception e) {
            if (e instanceof InvoiceServiceException invoiceServiceException) {
                throw invoiceServiceException;
            }
            log.error("Circuit breaker open for applyPaymentToInvoice({}): {}",
                    invoiceId, e.getMessage());
            throw new InvoiceServiceException(
                    "Invoice service unavailable (circuit breaker open). Payment application cannot proceed.",
                    503,
                    e);
        }
    }

    /**
     * Reverse payment application on invoice.
     * Restores invoice balance and updates status after accounting records
     * reversal.
     *
     * @param invoiceId invoice identifier
     * @param request   reversal details
     * @return invoice state after reversal
     * @throws InvoiceServiceException if operation fails
     */
    public ReversePaymentApplicationResponse reversePaymentApplication(
            UUID invoiceId,
            ReversePaymentApplicationRequest request) {

        log.info("Reversing payment application {} on invoice {}: amount={}, reason={}",
                request.getPaymentApplicationId(), invoiceId, request.getAmountToRestore(),
                request.getReason());

        Supplier<ReversePaymentApplicationResponse> invokeRemote = () -> {
            try {
                ReversePaymentApplicationResponse response = restClient.post()
                        .uri(invoiceServiceUrl + "/v1/invoices/{id}/reverse-payment", invoiceId)
                        .body(request)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                            log.warn("Invoice service error reversing payment: {} {}",
                                    httpResponse.getStatusCode(), httpResponse.getStatusText());
                            throw new InvoiceServiceException(
                                    "Invoice service returned " + httpResponse.getStatusCode(),
                                    httpResponse.getStatusCode().value());
                        })
                        .body(ReversePaymentApplicationResponse.class);

                if (response == null) {
                    log.warn("Invoice service returned null response for reverse payment on invoice {}",
                            invoiceId);
                    throw new InvoiceServiceException("No response from invoice service", 500);
                }

                log.info("Successfully reversed payment on invoice {}: new status={}, balanceDue={}",
                        invoiceId, response.getStatus(), response.getBalanceDue());

                return response;
            } catch (RestClientException e) {
                log.error("Failed to reverse payment on invoice {}: {}", invoiceId, e.getMessage(), e);
                throw new InvoiceServiceException(
                        "Failed to reverse payment on invoice: " + e.getMessage(), e);
            }
        };

        try {
            return invoiceServiceCircuitBreaker.executeSupplier(invokeRemote);
        } catch (Exception e) {
            if (e instanceof InvoiceServiceException invoiceServiceException) {
                throw invoiceServiceException;
            }
            log.error("Circuit breaker open for reversePaymentApplication({}): {}",
                    invoiceId, e.getMessage());
            throw new InvoiceServiceException(
                    "Invoice service unavailable (circuit breaker open). Payment reversal cannot proceed.",
                    503,
                    e);
        }
    }

    /**
     * Apply credit memo to invoice.
     * Updates invoice balance after credit memo is posted.
     *
     * @param invoiceId invoice identifier
     * @param request   credit memo application details
     * @return invoice state after credit memo applied
     * @throws InvoiceServiceException if operation fails
     */
    public ApplyCreditMemoResponse applyCreditMemo(
            UUID invoiceId,
            ApplyCreditMemoRequest request) {

        log.info("Applying credit memo {} to invoice {}: amount={}",
                request.getCreditMemoId(), invoiceId, request.getTotalAmount());

        Supplier<ApplyCreditMemoResponse> invokeRemote = () -> {
            try {
                ApplyCreditMemoResponse response = restClient.post()
                        .uri(invoiceServiceUrl + "/v1/invoices/{id}/apply-credit-memo", invoiceId)
                        .body(request)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                            log.warn("Invoice service error applying credit memo: {} {}",
                                    httpResponse.getStatusCode(), httpResponse.getStatusText());
                            throw new InvoiceServiceException(
                                    "Invoice service returned " + httpResponse.getStatusCode(),
                                    httpResponse.getStatusCode().value());
                        })
                        .body(ApplyCreditMemoResponse.class);

                if (response == null) {
                    log.warn("Invoice service returned null response for apply credit memo on invoice {}",
                            invoiceId);
                    throw new InvoiceServiceException("No response from invoice service", 500);
                }

                log.info("Successfully applied credit memo to invoice {}: new status={}, balanceAfter={}",
                        invoiceId, response.getStatus(), response.getBalanceAfter());

                return response;
            } catch (RestClientException e) {
                log.error("Failed to apply credit memo to invoice {}: {}", invoiceId, e.getMessage(), e);
                throw new InvoiceServiceException(
                        "Failed to apply credit memo to invoice: " + e.getMessage(), e);
            }
        };

        try {
            return invoiceServiceCircuitBreaker.executeSupplier(invokeRemote);
        } catch (Exception e) {
            if (e instanceof InvoiceServiceException invoiceServiceException) {
                throw invoiceServiceException;
            }
            log.error("Circuit breaker open for applyCreditMemo({}): {}",
                    invoiceId, e.getMessage());
            throw new InvoiceServiceException(
                    "Invoice service unavailable (circuit breaker open). Credit memo application cannot proceed.",
                    503,
                    e);
        }
    }

    /**
     * Check if Invoice service is healthy.
     * Used for pre-flight validation before attempting payment operations.
     *
     * @return true if service is reachable and healthy
     */
    public boolean isInvoiceServiceHealthy() {
        try {
            io.github.resilience4j.circuitbreaker.CircuitBreaker.State state = invoiceServiceCircuitBreaker.getState();
            log.debug("Invoice service circuit breaker state: {}", state);
            return state != io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
        } catch (Exception e) {
            log.debug("Invoice service health check failed: {}", e.getMessage());
            return false;
        }
    }
}
