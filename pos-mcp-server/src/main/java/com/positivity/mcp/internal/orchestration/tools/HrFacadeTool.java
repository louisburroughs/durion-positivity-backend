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
 * Facade over pos-people employee lookups.
 */
@Component
public class HrFacadeTool {

    private final RestClient employeeRestClient;
    private final RestClient availabilityRestClient;
    private final String employeeUriTemplate;
    private final String scheduleUriTemplate;
    private final String searchUriTemplate;

    public HrFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.hr.employee-base-url}") @NonNull String employeeBaseUrl,
            @Value("${pos.hr.availability-base-url}") @NonNull String availabilityBaseUrl,
            @Value("${pos.hr.employee-uri-template}") @NonNull String employeeUriTemplate,
            @Value("${pos.hr.schedule-uri-template}") @NonNull String scheduleUriTemplate,
            @Value("${pos.hr.search-uri-template}") @NonNull String searchUriTemplate) {
        this.employeeRestClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, employeeBaseUrl);
        this.availabilityRestClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, availabilityBaseUrl);
        this.employeeUriTemplate = employeeUriTemplate;
        this.scheduleUriTemplate = scheduleUriTemplate;
        this.searchUriTemplate = searchUriTemplate;
    }

    @Tool(description = "Get employee profile information by employee ID")
    public String getEmployee(@ToolParam(description = "The employee ID") @NonNull String employeeId) {
        return employeeRestClient
                .get()
                .uri(employeeUriTemplate, Map.of("employeeId", employeeId))
                .retrieve()
                .body(String.class);
    }

    @Tool(description = "Get current schedule details for a specific employee")
    public String getEmployeeSchedule(@ToolParam(description = "The employee ID") @NonNull String employeeId) {
        return availabilityRestClient
                .get()
                .uri(scheduleUriTemplate, Map.of("employeeId", employeeId))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Search employees by a case-insensitive substring match against first name, last "
                    + "name, preferred name, and employee number. query may be blank to list every employee. "
                    + "Use this tool for listing or typeahead lookups; use getEmployee instead when the "
                    + "employee's id is already known. Returns only the first page of matches (default size "
                    + "20).")
    public String searchEmployees(
            @ToolParam(description = "Case-insensitive substring match; blank lists all employees", required = false)
                    String query) {
        return employeeRestClient
                .get()
                .uri(searchUriTemplate, Map.of("query", query == null ? "" : query))
                .retrieve()
                .body(String.class);
    }
}
