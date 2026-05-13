package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HrFacadeTool {

    private final RestClient employeeRestClient;
    private final RestClient peopleRestClient;
    private final RestClient availabilityRestClient;
    private final String employeeUriTemplate;
    private final String searchUriTemplate;
    private final String scheduleUriTemplate;

    public HrFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.hr.employee-base-url}") @NonNull String employeeBaseUrl,
            @Value("${pos.hr.people-base-url}") @NonNull String peopleBaseUrl,
            @Value("${pos.hr.availability-base-url}") @NonNull String availabilityBaseUrl,
            @Value("${pos.hr.employee-uri-template}") @NonNull String employeeUriTemplate,
            @Value("${pos.hr.search-uri-template}") @NonNull String searchUriTemplate,
            @Value("${pos.hr.schedule-uri-template}") @NonNull String scheduleUriTemplate) {
        this.employeeRestClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, employeeBaseUrl);
        this.peopleRestClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, peopleBaseUrl);
        this.availabilityRestClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, availabilityBaseUrl);
        this.employeeUriTemplate = employeeUriTemplate;
        this.searchUriTemplate = searchUriTemplate;
        this.scheduleUriTemplate = scheduleUriTemplate;
    }

    @Tool("Get employee profile information by employee ID")
    public String getEmployee(@P("The employee ID") @NonNull String employeeId) {
        return employeeRestClient
                .get()
                .uri(employeeUriTemplate, Map.of("employeeId", employeeId))
                .retrieve()
                .body(String.class);
    }

    @Tool("Search employees by name, role, team, or availability")
    public String searchEmployees(@P("Search query for employees") @NonNull String query) {
        return peopleRestClient
                .get()
                .uri(searchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool("Get current schedule details for a specific employee")
    public String getEmployeeSchedule(@P("The employee ID") @NonNull String employeeId) {
        return availabilityRestClient
                .get()
                .uri(scheduleUriTemplate, Map.of("employeeId", employeeId))
                .retrieve()
                .body(String.class);
    }
}
