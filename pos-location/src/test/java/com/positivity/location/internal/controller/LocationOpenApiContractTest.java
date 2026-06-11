package com.positivity.location.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.location.BaseContractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

class LocationOpenApiContractTest extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("openApiSpec_locationEndpoints_haveRequiredSummaryAndDescription")
    @SuppressWarnings("unchecked")
    void openApiSpec_locationEndpoints_haveRequiredSummaryAndDescription() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();

        Map<String, Object> openApiSpec =
                objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) openApiSpec.get("paths");

        assertOperationDescription(paths, "/v1/travel-buffer-policies", "get");
        assertOperationDescription(paths, "/v1/travel-buffer-policies", "post");
        assertOperationDescription(paths, "/v1/travel-buffer-policies/{id}", "patch");
        assertOperationDescription(paths, "/v1/service-areas", "get");
        assertOperationDescription(paths, "/v1/service-areas", "post");
        assertOperationDescription(paths, "/v1/service-areas/{id}", "patch");
        assertOperationSummaryAndDescription(paths, "/v1/locations/{siteId}/storage-locations", "get");
        assertOperationSummaryAndDescription(paths, "/v1/locations/{siteId}/storage-locations", "post");
        assertOperationSummaryAndDescription(
                paths, "/v1/locations/{siteId}/storage-locations/{storageLocationId}", "get");
        assertOperationSummaryAndDescription(
                paths, "/v1/locations/{siteId}/storage-locations/{storageLocationId}", "patch");
    }

    @SuppressWarnings("unchecked")
    private static void assertOperationSummaryAndDescription(Map<String, Object> paths, String path, String method) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        assertThat(pathItem).as("path %s should exist", path).isNotNull();

        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        assertThat(operation)
                .as("%s %s operation should exist", method.toUpperCase(), path)
                .isNotNull();
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
        assertThat(operation)
                .as("%s %s operation should exist", method.toUpperCase(), path)
                .isNotNull();
        assertThat(operation.get("description"))
                .as("%s %s should have a non-empty description", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
    }
}
