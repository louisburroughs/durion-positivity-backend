package com.positivity.accounting.internal.client;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.positivity.shared.dto.InvoiceGenerationResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST client for invoice regeneration through pos-workorder.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkorderInvoiceClient {

    @Qualifier("invoiceServiceRestClient")
    private final RestClient restClient;

    @Value("${pos.workorder.service.url:http://pos-workorder:8080}")
    private String workorderServiceUrl;

    public InvoiceGenerationResponse regenerateInvoiceFromWorkorder(UUID workorderId, String idempotencyKey) {
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(workorderServiceUrl + "/v1/workorders/{workorderId}/generate-invoice", workorderId)
                    .header("X-User", "pos-accounting")
                    .header("X-Authorities", "workorder:workorder:generate_invoice");

            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                request.header("Idempotency-Key", idempotencyKey);
            }

            InvoiceGenerationResponse response = request.retrieve()
                    .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                        int status = httpResponse.getStatusCode().value();
                        throw new WorkorderServiceException(
                                "Workorder service returned " + status
                                        + " while regenerating invoice for workorder " + workorderId,
                                status);
                    })
                    .body(InvoiceGenerationResponse.class);

            if (response == null) {
                throw new WorkorderServiceException(
                        "Workorder service returned empty response for workorder " + workorderId,
                        500);
            }

            return response;
        } catch (WorkorderServiceException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("Failed to regenerate invoice for workorder {}: {}", workorderId, e.getMessage(), e);
            throw new WorkorderServiceException(
                    "Workorder service unavailable while regenerating invoice for workorder " + workorderId,
                    503,
                    e);
        }
    }
}
