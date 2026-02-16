package com.positivity.invoice.internal.client;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class TaxServiceClient {

    private static final Logger log = LoggerFactory.getLogger(TaxServiceClient.class);

    private final RestClient restClient;

    public TaxServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${invoice.tax.base-url:http://pos-tax:8090/v1/tax}") String taxServiceBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(taxServiceBaseUrl).build();
    }

    @NonNull
    public BigDecimal calculateTax(@NonNull BigDecimal subtotal, String partyId) {
        log.debug("Calculating tax for subtotal {} and partyId {}", subtotal, partyId);
        
        TaxCalculationResponse response = restClient.post()
                .uri("/calculate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "subtotal", subtotal,
                        "partyId", partyId == null ? "" : partyId))
                .retrieve()
                .body(TaxCalculationResponse.class);

        if (response == null) {
            throw new IllegalStateException("Tax service returned an empty response for tax calculation");
        }
        
        BigDecimal taxAmount = response.getTaxAmount();
        if (taxAmount == null) {
            throw new IllegalStateException("Tax service returned a null taxAmount in the response");
        }

        return taxAmount;
    }
}
