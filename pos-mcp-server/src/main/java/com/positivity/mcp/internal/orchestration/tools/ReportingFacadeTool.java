package com.positivity.mcp.internal.orchestration.tools;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ReportingFacadeTool {

    private final RestClient restClient;
    private final String salesReportUriTemplate;
    private final String inventoryReportUriTemplate;
    private final String agedReceivablesUriTemplate;

    public ReportingFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.reporting.base-url}") @NonNull String baseUrl,
            @Value("${pos.reporting.sales-report-uri-template}") @NonNull String salesReportUriTemplate,
            @Value("${pos.reporting.inventory-report-uri-template}") @NonNull String inventoryReportUriTemplate,
            @Value("${pos.reporting.aged-receivables-uri-template}") @NonNull String agedReceivablesUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.salesReportUriTemplate = salesReportUriTemplate;
        this.inventoryReportUriTemplate = inventoryReportUriTemplate;
        this.agedReceivablesUriTemplate = agedReceivablesUriTemplate;
    }

    @Tool(
            description = "Get the sales report (income statement) for a period. period must be a calendar "
                    + "month in YYYY-MM form (e.g. 2026-05) or a calendar year in YYYY form (e.g. 2026); it is "
                    + "mapped onto the report's start/end date range.")
    public String getSalesReport(@ToolParam(description = "Reporting period: YYYY-MM or YYYY") @NonNull String period) {
        ReportingPeriods.DateRange range = ReportingPeriods.toDateRange(period);
        return restClient
                .get()
                .uri(salesReportUriTemplate, Map.of("startDate", range.startDate(), "endDate", range.endDate()))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Get the inventory rollup report for a location by location id (UUID). The rollup "
                    + "aggregates a parent location's child sites — for a bare site with no children it is "
                    + "empty; use the location's on-hand inventory inquiry for site-level stock instead.")
    public String getInventoryReport(@ToolParam(description = "The location id (UUID)") @NonNull String locationId) {
        return restClient
                .get()
                .uri(inventoryReportUriTemplate, Map.of("locationId", locationId))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Get a composed revenue report for a period. period must be a calendar month in "
                    + "YYYY-MM form (e.g. 2026-05) or a calendar year in YYYY form (e.g. 2026). Returns a "
                    + "JSON envelope with two sections: incomeStatement (the period's income statement — its "
                    + "revenue lines are the earned revenue) and agedReceivables (per-customer open invoice "
                    + "balances bucketed by days past due, as of the period's end date — the revenue not yet "
                    + "collected). A failed or unauthorized aged-receivables section degrades only itself; "
                    + "the top-level status is degraded when the income statement is unavailable.")
    public String getRevenueReport(
            @ToolParam(description = "Reporting period: YYYY-MM or YYYY") @NonNull String period) {
        ReportingPeriods.DateRange range = ReportingPeriods.toDateRange(period);
        return ToolComposition.named("revenueReport")
                .call(
                        "incomeStatement",
                        () -> restClient
                                .get()
                                .uri(
                                        salesReportUriTemplate,
                                        Map.of("startDate", range.startDate(), "endDate", range.endDate()))
                                .retrieve()
                                .body(String.class))
                .require("incomeStatement")
                .call(
                        "agedReceivables",
                        () -> restClient
                                .get()
                                .uri(agedReceivablesUriTemplate, Map.of("asOfDate", range.endDate()))
                                .retrieve()
                                .body(String.class))
                .render();
    }
}
