package com.positivity.workorder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.workorder.support.BaseContractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

class WorkorderOpenApiContractTest extends BaseContractIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("openApiSpec_workorderEndpoints_haveRequiredSummaryAndDescription")
    @SuppressWarnings("unchecked")
    void openApiSpec_workorderEndpoints_haveRequiredSummaryAndDescription() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> openApiSpec = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) openApiSpec.get("paths");

        assertOperationSummaryAndDescription(paths, "/v1/workorders/{workorderId}/suggestSubstitutes", "post");
        assertOperationDescription(paths, "/v1/workorders/travelSegments/start", "post");
        assertOperationDescription(paths, "/v1/workorders/travelSegments/{travelSegmentId}/stop", "post");
        assertOperationDescription(paths, "/v1/workorders/travelSegments/submit/{mobileWorkAssignmentId}", "post");
        assertOperationDescription(paths, "/v1/workorders/travelSegments/{travelSegmentId}/adjustments", "post");
        assertOperationDescription(paths, "/v1/workorders/timeEntries/{timeEntryId}/approve", "post");
        assertOperationDescription(paths, "/v1/workorders/timeEntries/{timeEntryId}/reject", "post");
        assertOperationSummaryAndDescription(paths, "/v1/workorders/estimates/{estimateId}", "patch");
        assertOperationDescription(paths, "/v1/workorders/{workorderId}/picked-items", "get");
        assertOperationDescription(paths, "/v1/workorders/{workorderId}/pick-list", "get");
        assertOperationDescription(paths, "/v1/workorders/{workorderId}/pick-list/tasks", "get");
        assertOperationDescription(paths, "/v1/workorders/{workorderId}/pick-tasks/{pickTaskId}:resolve-scan", "post");
        assertOperationDescription(
                paths, "/v1/workorders/{workorderId}/pick-tasks/{pickTaskId}/lines/{pickLineId}:confirm", "post");
        assertOperationDescription(paths, "/v1/workorders/{workorderId}/pick-tasks/{pickTaskId}:complete", "post");
        assertOperationDescription(paths, "/v1/workorders/{workorderId}/picked-items:consume", "post");
    }

    @SuppressWarnings("unchecked")
    private static void assertOperationSummaryAndDescription(Map<String, Object> paths, String path, String method) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        assertThat(pathItem).as("path %s should exist", path).isNotNull();

        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        assertThat(operation).as("%s %s operation should exist", method.toUpperCase(), path).isNotNull();
        assertThat(operation.get("summary"))
                .as("%s %s should have a non-empty summary", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
        assertThat(operation.get("description"))
                .as("%s %s should have a non-empty description", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
    }

    @SuppressWarnings("unchecked")
    private static void assertOperationDescription(Map<String, Object> paths, String path, String method) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        assertThat(pathItem).as("path %s should exist", path).isNotNull();

        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        assertThat(operation).as("%s %s operation should exist", method.toUpperCase(), path).isNotNull();
        assertThat(operation.get("description"))
                .as("%s %s should have a non-empty description", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
    }
}
