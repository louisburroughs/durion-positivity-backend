package com.positivity.mcp.internal.orchestration.tools;

import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.annotation.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TaxFacadeTool {

    private final RestClient restClient;
    private final String taxRateUriTemplate;
    private final String taxCalculateUriTemplate;
    private final String taxSummaryUriTemplate;

    public TaxFacadeTool(
            RestClient.Builder restClientBuilder,
            @Value("${pos.tax.base-url}") @NonNull String baseUrl,
            @Value("${pos.tax.tax-rate-uri-template}") @NonNull String taxRateUriTemplate,
            @Value("${pos.tax.tax-calculate-uri-template}") @NonNull String taxCalculateUriTemplate,
            @Value("${pos.tax.tax-summary-uri-template}") @NonNull String taxSummaryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.taxRateUriTemplate = taxRateUriTemplate;
        this.taxCalculateUriTemplate = taxCalculateUriTemplate;
        this.taxSummaryUriTemplate = taxSummaryUriTemplate;
    }

    @Tool(description = "Get the tax rate for a specific location")
    public String getTaxRate(@ToolParam(description = "The location ID") @NonNull String locationId) {
        return restClient
                .get()
                .uri(taxRateUriTemplate, Map.of("locationId", locationId))
                .retrieve()
                .body(String.class);
    }

    @Tool(description = "Calculate estimated tax amount for an amount and location")
    public String calculateTax(
            @ToolParam(description = "The taxable amount") @NonNull String amount, @ToolParam(description = "The location ID") @NonNull String locationId) {
        return restClient
                .get()
                .uri(taxCalculateUriTemplate, Map.of("amount", amount, "locationId", locationId))
                .retrieve()
                .body(String.class);
    }

    @Tool(description = "Get tax summary details for a reporting period")
    public String getTaxSummary(@ToolParam(description = "Tax reporting period") @NonNull String period) {
        return restClient
                .get()
                .uri(taxSummaryUriTemplate, Map.of("period", period))
                .retrieve()
                .body(String.class);
    }
}
