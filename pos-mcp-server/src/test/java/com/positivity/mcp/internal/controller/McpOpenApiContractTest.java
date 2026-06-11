package com.positivity.mcp.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"eureka.client.enabled=false", "pos.security.permission-registration.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpOpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("openApiSpec_mcpEndpoints_haveRequiredSummaryAndDescription")
    @SuppressWarnings("unchecked")
    void openApiSpec_mcpEndpoints_haveRequiredSummaryAndDescription() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();

        Map<String, Object> openApiSpec =
                objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) openApiSpec.get("paths");

        assertOperationSummaryAndDescription(paths, "/v1/prompts", "get");
        assertOperationSummaryAndDescription(paths, "/v1/prompts/{id}", "get");
        assertOperationSummaryAndDescription(paths, "/v1/prompts", "post");
        assertOperationSummaryAndDescription(paths, "/v1/prompts/{id}", "put");
        assertOperationSummaryAndDescription(paths, "/v1/prompts/{id}", "delete");
        assertOperationDescription(paths, "/v1/nlt/requests", "post");
        assertOperationDescription(paths, "/v1/mcp/chat", "post");
        assertOperationDescription(paths, "/v1/mcp/chat/stream", "post");
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
