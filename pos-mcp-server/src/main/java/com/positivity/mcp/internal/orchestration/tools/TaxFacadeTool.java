package com.positivity.mcp.internal.orchestration.tools;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Facade over tax lookups.
 *
 * <p>Direct-call exception (#641): the rate/calculate lookups inject the plain {@code @Primary}
 * builder, not {@code loadBalancedRestClientBuilder} — pos-tax is internal-only and sets
 * {@code register-with-eureka: false} (ADR-0021), so neither Eureka resolution nor the gateway
 * route can reach it. That base URL stays an explicit Docker DNS address.
 *
 * <p>{@link #getTaxSummary} is the exception to the exception (#1519 Wave 2): the tax summary is
 * pos-accounting's tax-liability report, served through the gateway, so it uses a second,
 * load-balanced client against {@code pos.tax.summary-base-url}.
 */
@Component
public class TaxFacadeTool {

    private final RestClient restClient;
    private final RestClient summaryRestClient;
    private final String taxRateUriTemplate;
    private final String taxCalculateUriTemplate;
    private final String taxSummaryUriTemplate;

    public TaxFacadeTool(
            RestClient.Builder restClientBuilder,
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${pos.tax.base-url}") @NonNull String baseUrl,
            @Value("${pos.tax.summary-base-url}") @NonNull String summaryBaseUrl,
            @Value("${pos.tax.tax-rate-uri-template}") @NonNull String taxRateUriTemplate,
            @Value("${pos.tax.tax-calculate-uri-template}") @NonNull String taxCalculateUriTemplate,
            @Value("${pos.tax.tax-summary-uri-template}") @NonNull String taxSummaryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.summaryRestClient =
                ToolRestClientSupport.instrumentedClient(loadBalancedRestClientBuilder, summaryBaseUrl);
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
            @ToolParam(description = "The taxable amount") @NonNull String amount,
            @ToolParam(description = "The location ID") @NonNull String locationId) {
        return restClient
                .get()
                .uri(taxCalculateUriTemplate, Map.of("amount", amount, "locationId", locationId))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Get the sales-tax liability summary for a reporting period: per-jurisdiction taxable "
                    + "base, exempt base, tax collected, credit reversals, and net tax. period must be a "
                    + "calendar month in YYYY-MM form (e.g. 2026-05) or a calendar year in YYYY form (e.g. "
                    + "2026); it is mapped onto the report's start/end date range.")
    public String getTaxSummary(
            @ToolParam(description = "Tax reporting period: YYYY-MM or YYYY") @NonNull String period) {
        ReportingPeriods.DateRange range = ReportingPeriods.toDateRange(period);
        return summaryRestClient
                .get()
                .uri(taxSummaryUriTemplate, Map.of("startDate", range.startDate(), "endDate", range.endDate()))
                .retrieve()
                .body(String.class);
    }
}
