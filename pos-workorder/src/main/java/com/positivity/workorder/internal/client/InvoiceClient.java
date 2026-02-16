package com.positivity.workorder.internal.client;

import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * REST client for communication with pos-invoice service.
 */
@Component
@RequiredArgsConstructor
public class InvoiceClient {

    private final RestClient invoiceServiceRestClient;

    @NonNull
    public InvoiceGenerationResponse createInvoice(@NonNull InvoiceCreationRequest request) {
        InvoiceGenerationResponse response = invoiceServiceRestClient.post()
                .uri("/v1/invoices")
                .body(request)
                .retrieve()
                .body(InvoiceGenerationResponse.class);

        if (response == null) {
            throw new IllegalStateException("Invoice service returned an empty response for invoice generation");
        }

        return response;
    }
}
