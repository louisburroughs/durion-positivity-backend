package com.positivity.invoice.internal.client;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class TaxServiceClient {

    private static final Logger log = LoggerFactory.getLogger(TaxServiceClient.class);

    private final RestClient restClient;
    private final boolean useStub;
    private final BigDecimal stubRate;

    public TaxServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${invoice.tax.base-url:http://pos-tax:8090/v1/tax}") String taxServiceBaseUrl,
            @Value("${invoice.tax.use-stub:true}") boolean useStub,
            @Value("${invoice.tax.stub-rate:0.10}") BigDecimal stubRate) {
        this.restClient = restClientBuilder.baseUrl(taxServiceBaseUrl).build();
        this.useStub = useStub;
        this.stubRate = stubRate;
    }

    @NonNull
    public TaxCalculationResponse calculateTax(@NonNull TaxCalculationRequest request) {
        if (useStub) {
            return calculateTaxStub(request);
        }

        try {
            TaxCalculationResponse response = restClient.post()
                    .uri("/calculate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(TaxCalculationResponse.class);

            if (response == null || response.getTaxAmount() == null) {
                log.warn("Tax service returned null response; falling back to stub tax calculation");
                return calculateTaxStub(request);
            }

            return response;
        } catch (Exception ex) {
            log.warn("Tax service unavailable; falling back to stub tax calculation: {}", ex.getMessage());
            return calculateTaxStub(request);
        }
    }

    @NonNull
    private TaxCalculationResponse calculateTaxStub(@NonNull TaxCalculationRequest request) {
        BigDecimal subtotal = request.getSubtotal() == null ? BigDecimal.ZERO : request.getSubtotal();
        BigDecimal taxAmount = subtotal.multiply(stubRate).setScale(4, RoundingMode.HALF_UP);

        TaxCalculationResponse response = new TaxCalculationResponse();
        response.setRate(stubRate);
        response.setTaxAmount(taxAmount);
        return response;
    }
}
