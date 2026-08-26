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
 * Facade over pos-people employee lookups. Employee search was removed (#1523): pos-people
 * publishes no employee list/search endpoint, so the former searchEmployees tool could never
 * resolve; it returns once the real endpoint exists.
 */
@Component
public class HrFacadeTool {

    private final RestClient employeeRestClient;
    private final RestClient availabilityRestClient;
    private final String employeeUriTemplate;
    private final String scheduleUriTemplate;

    public HrFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.hr.employee-base-url}") @NonNull String employeeBaseUrl,
            @Value("${pos.hr.availability-base-url}") @NonNull String availabilityBaseUrl,
            @Value("${pos.hr.employee-uri-template}") @NonNull String employeeUriTemplate,
            @Value("${pos.hr.schedule-uri-template}") @NonNull String scheduleUriTemplate) {
        this.employeeRestClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, employeeBaseUrl);
        this.availabilityRestClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, availabilityBaseUrl);
        this.employeeUriTemplate = employeeUriTemplate;
        this.scheduleUriTemplate = scheduleUriTemplate;
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
}
