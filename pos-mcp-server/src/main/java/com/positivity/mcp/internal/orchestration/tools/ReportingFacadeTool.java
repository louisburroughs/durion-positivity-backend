package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

@Component
public class ReportingFacadeTool {

    private final RestClient restClient;
    private final String salesReportUriTemplate;
    private final String inventoryReportUriTemplate;
    private final String revenueReportUriTemplate;

    public ReportingFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.reporting.base-url}") @NonNull String baseUrl,
            @Value("${pos.reporting.sales-report-uri-template}") @NonNull String salesReportUriTemplate,
            @Value("${pos.reporting.inventory-report-uri-template}") @NonNull String inventoryReportUriTemplate,
            @Value("${pos.reporting.revenue-report-uri-template}") @NonNull String revenueReportUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.salesReportUriTemplate = salesReportUriTemplate;
        this.inventoryReportUriTemplate = inventoryReportUriTemplate;
        this.revenueReportUriTemplate = revenueReportUriTemplate;
    }

    @Tool("Get a sales report for a requested period")
    public String getSalesReport(@P("Reporting period identifier") @NonNull String period) {
        return restClient
                .get()
                .uri(salesReportUriTemplate, Map.of("period", period))
                .retrieve()
                .body(String.class);
    }

    @Tool("Get an inventory report for a specific location")
    public String getInventoryReport(@P("The location ID") @NonNull String locationId) {
        return restClient
                .get()
                .uri(inventoryReportUriTemplate, Map.of("locationId", locationId))
                .retrieve()
                .body(String.class);
    }

    @Tool("Get a revenue report for a requested period")
    public String getRevenueReport(@P("Reporting period identifier") @NonNull String period) {
        return restClient
                .get()
                .uri(revenueReportUriTemplate, Map.of("period", period))
                .retrieve()
                .body(String.class);
    }
}
