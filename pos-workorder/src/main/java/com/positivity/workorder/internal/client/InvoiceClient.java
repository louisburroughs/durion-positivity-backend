package com.positivity.workorder.internal.client;

import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * REST client for communication with pos-invoice service.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvoiceClient {

    private final RestClient invoiceServiceRestClient;

    @NonNull
    public InvoiceGenerationResponse createInvoice(@NonNull InvoiceCreationRequest request) {
        InvoiceGenerationResponse response = invoiceServiceRestClient
                .post()
                .uri("/v1/invoices")
                .header("X-User", "pos-workorder")
                .header("X-Authorities", "invoice:manage")
                .body(request)
                .retrieve()
                .body(InvoiceGenerationResponse.class);

        if (response == null) {
            throw new IllegalStateException("Invoice service returned an empty response for invoice generation");
        }

        return response;
    }

    @NonNull
    public InvoiceGenerationResponse getInvoice(@NonNull UUID invoiceId) {
        log.debug("Fetching invoice details from pos-invoice service for invoice {}", invoiceId);

        Map<String, Object> invoiceData = invoiceServiceRestClient
                .get()
                .uri("/v1/invoices/{invoiceId}", invoiceId)
                .header("X-User", "pos-workorder")
                .header("X-Authorities", "invoice:manage")
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (invoiceData == null) {
            throw new IllegalStateException("Invoice service returned an empty response for invoice " + invoiceId);
        }

        return mapToInvoiceGenerationResponse(invoiceData);
    }

    @NonNull
    private InvoiceGenerationResponse mapToInvoiceGenerationResponse(@NonNull Map<String, Object> data) {
        InvoiceGenerationResponse response = new InvoiceGenerationResponse();

        response.setInvoiceId(parseUUID(data.get("invoiceId")));
        response.setStatus(parseString(data.get("status")));
        response.setWorkorderId(parseUUID(data.get("workorderId")));
        response.setEstimateId(parseUUID(data.get("estimateId")));
        response.setApprovalId(parseUUID(data.get("approvalId")));
        response.setSubtotal(parseBigDecimal(data.get("subtotal")));
        // Invoice service returns 'tax' and 'total' field names
        response.setTaxAmount(parseBigDecimal(data.get("tax")));
        response.setTotalAmount(parseBigDecimal(data.get("total")));
        response.setCreatedAt(parseInstant(data.get("createdAt")));

        return response;
    }

    private UUID parseUUID(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return UUID.fromString(s);
        }
        return null;
    }

    private String parseString(Object value) {
        return value != null ? value.toString() : null;
    }

    private BigDecimal parseBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            if (value instanceof String s) {
                return new BigDecimal(s);
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse BigDecimal from value: {}", value, e);
            return null;
        }
        return null;
    }

    private Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof String s) {
                return Instant.parse(s);
            }
            if (value instanceof Number number) {
                return Instant.ofEpochMilli(number.longValue());
            }
        } catch (Exception e) {
            log.warn("Failed to parse Instant from value: {}", value, e);
            return null;
        }
        return null;
    }
}
