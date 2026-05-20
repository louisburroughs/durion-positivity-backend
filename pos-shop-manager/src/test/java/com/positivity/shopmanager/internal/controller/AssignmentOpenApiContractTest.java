package com.positivity.shopmanager.internal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.BaseContractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

class AssignmentOpenApiContractTest extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("openApiSpec_assignmentEndpoints_haveSummaryAndDescription")
    @SuppressWarnings("unchecked")
    void openApiSpec_assignmentEndpoints_haveSummaryAndDescription() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        Map<String, Object> openApiSpec = objectMapper.readValue(result.getResponse().getContentAsByteArray(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) openApiSpec.get("paths");

        assertOperationSummaryAndDescription(paths, "/v1/appointments/{appointmentId}/assignments", "get");
        assertOperationSummaryAndDescription(paths, "/v1/appointments/{appointmentId}/assignments", "post");
    }

    @SuppressWarnings("unchecked")
    private static void assertOperationSummaryAndDescription(Map<String, Object> paths, String path, String method) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        org.assertj.core.api.Assertions.assertThat(pathItem).as("path %s should exist", path).isNotNull();

        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        org.assertj.core.api.Assertions.assertThat(operation)
                .as("%s %s operation should exist", method.toUpperCase(), path)
                .isNotNull();
        org.assertj.core.api.Assertions.assertThat(operation.get("summary"))
                .as("%s %s should have a non-empty summary", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
        org.assertj.core.api.Assertions.assertThat(operation.get("description"))
                .as("%s %s should have a non-empty description", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
    }
}
